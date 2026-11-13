package com.example.fashion_backend.service;

import com.example.fashion_backend.repository.*;
import com.example.fashion_backend.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, read-only export. No Spring application startup or Flyway. */
@EnabledIfEnvironmentVariable(named = "PERSONALIZATION_EXPORT_REPORT", matches = "true")
class ExportPersonalizationReportTest {
    @Test void exportCurrentUsersUsingTheActualRankingCode() throws Exception {
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(reader); }
        if (Files.exists(Path.of(".env"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env"))) { config.load(reader); }
        }
        var ds = new DriverManagerDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            }
        };
        ds.setUrl(System.getenv().getOrDefault("PERSONALIZATION_REPORT_DB_URL", config.getProperty("spring.datasource.url")));
        ds.setUsername(System.getenv().getOrDefault("PERSONALIZATION_REPORT_DB_USER", config.getProperty("spring.datasource.username")));
        ds.setPassword(System.getenv().getOrDefault("PERSONALIZATION_REPORT_DB_PASSWORD", config.getProperty("spring.datasource.password")));
        boolean hasViews;
        try (var connection = ds.getConnection(); var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT to_regclass('user_product_views') IS NOT NULL")) {
            result.next(); hasViews = result.getBoolean(1);
        }
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds); factory.setPackagesToScan("com.example.fashion_backend.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false"));
        factory.afterPropertiesSet();
        List<Map<String,Object>> output=new ArrayList<>();
        OffsetDateTime at=OffsetDateTime.now(ZoneOffset.UTC);
        var index=new ProductSimilarityIndex(Path.of(System.getenv().getOrDefault("RECOMMENDATION_MODA_INDEX",
                "../fashion-ai/artifacts/recommendations/moda-v1")));
        assertTrue(index.isAvailable(), "Report requires a validated MODA artifact: "+index.failure());
        try (var em=factory.getObject().createEntityManager()) {
            em.getTransaction().begin();
            assertEquals("on",em.createNativeQuery("SHOW transaction_read_only",String.class).getSingleResult());
            var repositories=new JpaRepositoryFactory(em);
            var users=repositories.getRepository(UserRepository.class);
            var products=repositories.getRepository(ProductRepository.class);
            var cartRepo=repositories.getRepository(CartRepository.class);
            var orders=repositories.getRepository(OrderRepository.class);
            var views=hasViews?repositories.getRepository(UserProductViewRepository.class):mock(UserProductViewRepository.class);
            var profiles=new UserProfileService(users,products,views);
            var catalogService=new ProductService(products);
            var cartService=new CartService(cartRepo,products);
            var clock=Clock.fixed(at.toInstant(),ZoneOffset.UTC);
            var service=new RecommendationService(catalogService,profiles,cartService,orders,views,index,clock);
            var baseline=new MetadataRecommendationBaseline(catalogService,profiles,cartService,orders,views,clock);
            var ids=new ArrayList<UUID>(users.findAll().stream().map(UserEntity::getId).sorted().toList());
            ids.add(null);
            for (UUID id:ids) {
                var a=service.assess(12,null,null,null,null,List.of(),id);
                var previous=baseline.assess(12,null,null,null,null,List.of(),id);
                var entry=snapshot(a);
                entry.put("baselineTop12",previous.decisions().stream().filter(d->d.exclusions().isEmpty()).limit(12)
                        .map(d->Map.of("productId",d.product().getId(),"title",d.product().getTitle(),"score",d.score())).toList());
                entry.put("baselineAlgorithm","metadata_1_3_5_profile30_behavior60_popularity10");
                entry.put("baselineInterests",previous.interests());
                entry.put("baselineTimestamp",at.toString());
                Set<String> oldIds=previous.decisions().stream().filter(d->d.exclusions().isEmpty()).limit(12)
                        .map(d->d.product().getId()).collect(java.util.stream.Collectors.toSet());
                entry.put("top12Overlap",a.decisions().stream().filter(d->d.exclusions().isEmpty()).limit(12)
                        .filter(d->oldIds.contains(d.product().getId())).count());
                output.add(entry);
            }
            em.getTransaction().rollback();
        } finally { factory.destroy(); }
        Path directory=Path.of(System.getProperty("personalization.report.output","../reports/personalization/moda-v1"));
        if (Files.exists(directory)) throw new IllegalStateException("Choose a NEW report directory: "+directory);
        Files.createDirectories(directory);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("generatedAt",at.toString()); report.put("readOnly",true); report.put("viewTableAvailable",hasViews);
        report.put("indexVersion",index.version()); report.put("indexedProducts",index.productCount());
        report.put("activationThreshold",3); report.put("purchaseHistoryDays",360); report.put("viewHalfLifeDays",7);
        report.put("purchaseHalfLifeDays",60); report.put("cartDecay",false); report.put("users",output);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve("evaluation.json").toFile(),report);
        Files.writeString(directory.resolve("index.html"),html(output,at,hasViews,index.version()),StandardCharsets.UTF_8);
        System.out.println("Read-only MODA report: "+directory+", profiles="+output.size());
    }

    private Map<String,Object> snapshot(RecommendationService.Assessment a) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("userId",a.userId()); row.put("mode",a.mode()); row.put("fallbackReason",a.fallbackReason());
        row.put("behaviorStrength",a.behaviorStrength()); row.put("rawStrength",a.rawStrength()); row.put("activationThreshold",3);
        row.put("effectiveWeights",a.interests()); row.put("rawWeights",a.rawInterests());
        row.put("inCart",a.inCart()); row.put("recentPurchases",a.recentlyPurchased());
        Map<String,Object> profile=new LinkedHashMap<>();
        if(a.profile()!=null) {
            profile.put("categories",a.profile().getPreferredCategories()); profile.put("min",a.profile().getPriceMin());
            profile.put("max",a.profile().getPriceMax()); profile.put("gender",a.profile().getGender());
        }
        row.put("profile",profile);
        row.put("signals",a.signals().stream().map(s->{
            Map<String,Object> source=new LinkedHashMap<>(); source.put("productId",s.productId()); source.put("source",s.source());
            source.put("timestamp",Objects.toString(s.at(),null)); source.put("baseWeight",s.baseWeight()); source.put("decayedWeight",s.decayedWeight());
            source.put("reason",s.reason()); source.put("orderId",s.orderId());
            source.put("effectiveWeightForProduct",a.interests().get(s.productId()));
            return source;
        }).toList());
        List<Map<String,Object>> candidates=new ArrayList<>(); int rank=0;
        for(var d:a.decisions()) {
            Map<String,Object> c=new LinkedHashMap<>();
            c.put("rank",d.exclusions().isEmpty()?++rank:null); c.put("selected",d.exclusions().isEmpty()&&rank<=a.limit());
            c.put("productId",d.product().getId()); c.put("title",d.product().getTitle()); c.put("soldCount",d.product().getSoldCount());
            c.put("behaviorScore",d.behaviorScore()); c.put("purchaseMultiplier",d.purchaseMultiplier()); c.put("score",d.score());
            c.put("purchaseOrderId",d.purchaseOrderId()); c.put("personalized",d.personalized());
            c.put("selectionSource",d.personalized()?"personalized":a.mode().equals("PERSONALIZED")?"fallback-fill":"fallback");
            c.put("fallbackReason",d.fallbackReason()); c.put("exclusions",d.exclusions());
            boolean categoryDeclared=a.profile()!=null && a.profile().getPreferredCategories()!=null
                    && a.profile().getPreferredCategories().stream().anyMatch(v->v!=null&&!v.isBlank());
            boolean budgetDeclared=a.profile()!=null && (a.profile().getPriceMin()!=null || a.profile().getPriceMax()!=null);
            boolean genderDeclared=a.profile()!=null && a.profile().getGender()!=null && !a.profile().getGender().isBlank();
            c.put("profileMatch",Arrays.asList(categoryDeclared?d.profileMatch().category():null,
                    budgetDeclared?d.profileMatch().budget():null,genderDeclared?d.profileMatch().gender():null));
            c.put("contributions",d.sourceSimilarities().entrySet().stream().map(e->Map.of("sourceId",e.getKey(),"cosine",e.getValue(),
                    "weight",a.interests().get(e.getKey()),"normalizedWeight",a.interests().get(e.getKey())/a.behaviorStrength(),
                    "weightedContribution",e.getValue()*a.interests().get(e.getKey())/a.behaviorStrength())).toList());
            // Same score is compared without rounding. The first differing Profile key is the actual tie-break.
            String tieBreak=null;
            if(d.personalized()&&d.exclusions().isEmpty()) for(var other:a.decisions()) {
                if(!other.personalized()||!other.exclusions().isEmpty()||other.product().getId().equals(d.product().getId())
                        || Double.compare(d.score(),other.score())!=0) continue;
                tieBreak=d.profileMatch().category()!=other.profileMatch().category()?"category":
                        d.profileMatch().budget()!=other.profileMatch().budget()?"budget":
                        d.profileMatch().gender()!=other.profileMatch().gender()?"gender":"productId";
                if(!tieBreak.equals("productId")) break;
            }
            c.put("tieBreakCriterion",tieBreak); candidates.add(c);
        }
        row.put("candidates",candidates); return row;
    }

    private String html(List<Map<String,Object>> users,OffsetDateTime at,boolean hasViews,String version) {
        StringBuilder out=new StringBuilder("""
        <!doctype html><html lang="vi"><meta charset="utf-8"><title>Cá nhân hoá MODA — báo cáo kiểm chứng</title>
        <style>body{font:15px system-ui;color:#172033;background:#f8fafc;margin:24px auto;max-width:1500px;padding:0 20px}section{background:white;border:1px solid #ddd;border-radius:12px;padding:18px;margin:20px 0;overflow:auto}table{border-collapse:collapse;width:100%;font-size:13px}th,td{padding:8px;text-align:left;border-bottom:1px solid #ddd}th{background:#eef2f7}code,pre{overflow-wrap:anywhere;white-space:pre-wrap}summary{cursor:pointer}</style>
        <h1>Cá nhân hoá: Behavior + MODA</h1><p>Snapshot database chỉ đọc. Baseline metadata và phương án mới dùng cùng transaction và cùng thời điểm tính decay. Không xuất tên/email/điện thoại. Không khẳng định backend đang chạy đã dùng source mới.</p>
        <p>View/Cart/Purchase = 1/5/10; View 7 ngày, Purchase 60 ngày; Purchase chỉ trong 180 ngày theo createdAt. Cart không decay: giỏ lâu ngày vẫn có trọng số 5.</p>
        <p>MODA khi strength ≥ 3; điểm là trung bình cosine có dấu theo trọng số. Ngưỡng 3 không phải trọng số. Chỉ đúng ID mua trong 30 ngày nhận hệ số 0.25. Profile chỉ phá hoà khi điểm bằng nhau, vì vậy có thể ít/không ảnh hưởng. Fallback: Profile → soldCount.</p>
        <p>Cột khớp loại/giá/gender: null nghĩa là user chưa khai báo, không phải không thích. Trong nhánh MODA, soldCount chỉ để đối chiếu và không tham gia xếp hạng.</p>
        <p>Artifact tái sử dụng 471 ảnh/124 sản phẩm, không inference. Audit hiện tại ghi nhận 952 mapping ảnh mới chưa có trong index; độ phủ productId không đồng nghĩa toàn bộ ảnh mới đã được encode.</p>
        <p><a href="evaluation.json">JSON đầy đủ</a> · <a href="../../../fashion-backend/PERSONALIZATION_MODA.md">Cách chạy và giới hạn</a></p>
        """);
        out.append("<p>Snapshot: ").append(esc(at.atZoneSameInstant(ZoneId.of("Asia/Bangkok")))).append("; index: <code>").append(esc(version)).append("</code>; views available=").append(hasViews).append(".</p>");
        for(var u:users) {
            out.append("<section><h2>").append(esc(u.get("userId")==null?"Khách / người mới":u.get("userId"))).append("</h2><p>Nhánh: ")
                .append(esc(u.get("mode"))).append("; lý do: ").append(esc(u.get("fallbackReason"))).append("; strength dùng được: ")
                .append(esc(u.get("behaviorStrength"))).append("; trước kiểm tra embedding: ").append(esc(u.get("rawStrength"))).append("; gate=3.</p><p>Profile: ")
                .append(esc(u.get("profile"))).append(".</p><details><summary>Nguồn và trọng số hiệu lực</summary><pre>")
                .append(esc(u.get("signals"))).append("</pre><pre>").append(esc(u.get("effectiveWeights"))).append("</pre></details>");
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)u.get("candidates");
            out.append("<h3>Top 12 mới</h3>").append(table(rows.stream().filter(c->Boolean.TRUE.equals(c.get("selected"))).toList()));
            out.append("<p>Số sản phẩm Top 12 trùng baseline cùng snapshot: ").append(esc(u.get("top12Overlap"))).append(" / 12.</p>");
            out.append("<details><summary>Tất cả ứng viên / lý do loại</summary>").append(table(rows)).append("</details>");
            out.append("<details><summary>Baseline metadata trên cùng snapshot</summary><pre>").append(esc(u.get("baselineTop12"))).append("</pre></details></section>");
        }
        return out.append("</html>").toString();
    }
    private String table(List<Map<String,Object>> rows) {
        StringBuilder out=new StringBuilder("<table><tr><th>Hạng</th><th>Sản phẩm</th><th>Nhánh</th><th>B</th><th>Hệ số mua</th><th>Điểm cuối</th><th>Khớp loại/giá/gender</th><th>soldCount (fallback)</th><th>Giải thích</th></tr>");
        for(var c:rows) {
            out.append("<tr><td>").append(esc(c.get("rank"))).append("</td><td>").append(esc(c.get("title"))).append("<br><code>").append(esc(c.get("productId"))).append("</code></td>");
            for(String key:List.of("selectionSource","behaviorScore","purchaseMultiplier","score","profileMatch","soldCount"))
                out.append("<td>").append(esc(c.get(key))).append("</td>");
            out.append("<td>").append(esc(c.get("exclusions"))).append(" ").append(esc(c.get("fallbackReason"))).append("; tie: ")
                .append(esc(c.get("tieBreakCriterion"))).append("<details><summary>Cosine và đóng góp từng nguồn</summary><pre>")
                .append(esc(c.get("contributions"))).append("</pre></details></td></tr>");
        }
        return out.append("</table>").toString();
    }
    private String esc(Object value) {
        return Objects.toString(value,"—").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
