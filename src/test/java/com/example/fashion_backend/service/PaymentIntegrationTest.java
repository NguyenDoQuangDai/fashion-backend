package com.example.fashion_backend.service;

import com.example.fashion_backend.config.*;
import com.example.fashion_backend.controller.*;
import com.example.fashion_backend.controller.admin.*;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.repository.*;
import com.example.fashion_backend.security.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Explicit allowlist: never boot this test against application.properties' database.
@EnabledIfEnvironmentVariable(named = "PHASE3_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55439/phase3_test")
@SpringBootTest(classes = PaymentIntegrationTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIntegrationTest {
    static final String SCHEMA = "vnpay_" + UUID.randomUUID().toString().replace("-", "");
    static String legacyOrders, legacyItems, stockBefore;
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired JdbcTemplate sql;
    @Autowired VnpayService gateway;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired VnpayConfig config;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = OrderEntity.class)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({InventoryService.class, AdminProductService.class, OrderService.class, PaymentService.class, VnpayService.class, VnpayConfig.class, CheckoutController.class, CartService.class,
            OrderController.class, VnpayPaymentController.class, AdminOrderController.class, AdminPaymentController.class,
            GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, UserDetailsServiceImpl.class,
            ProductService.class, UserProfileService.class, RecommendationService.class, ProductSimilarityIndex.class,
            RecommendationController.class, UserProfileController.class, CartController.class})
    static class TestApp {
        @Bean MailService mail() { return mock(MailService.class); }
        @Bean DeliveryAddressService addresses() {
            var s = mock(DeliveryAddressService.class);
            when(s.resolve(any())).thenReturn(new DeliveryAddressService.ResolvedAddress("Test address", "test-place", new BigDecimal("21"), new BigDecimal("105"), null));
            return s;
        }
        @Bean ShippingService shipping() {
            var s = mock(ShippingService.class);
            when(s.quote(any())).thenReturn(new ShippingService.ShippingQuote(new BigDecimal("6000.01"), new BigDecimal("6000")));
            return s;
        }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("PHASE3_TEST_DB_URL");
        try (var db = DriverManager.getConnection(url, "phase3_test", ""); var s = db.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public"); s.execute("ALTER EXTENSION pg_trgm SET SCHEMA public");
        }
        Flyway.configure().dataSource(url, "phase3_test", "").schemas(SCHEMA).locations("classpath:db/migration").target("15").load().migrate();
        try (var db = DriverManager.getConnection(url, "phase3_test", ""); var s = db.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA + ",public");
            s.execute("INSERT INTO orders(id,status,total,note) SELECT gen_random_uuid(),status,123456.78,'legacy-vnpay-test' FROM unnest(ARRAY['processing','shipping','completed','cancelled']) status");
            s.execute("INSERT INTO products(id,title) VALUES('legacy-vnpay-product','Legacy')");
            s.execute("INSERT INTO order_items(order_id,product_id,quantity,price_snapshot) SELECT id,'legacy-vnpay-product',2,50000 FROM orders");
            legacyOrders = scalar(s, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM orders o");
            legacyItems = scalar(s, "SELECT jsonb_agg(to_jsonb(i) ORDER BY id)::text FROM order_items i");
        }
        Flyway.configure().dataSource(url, "phase3_test", "").schemas(SCHEMA).locations("classpath:db/migration").load().migrate();
        // A separate pre-M1 schema proves V19 never rewrites existing stock.
        String migrationSchema=SCHEMA+"_m1";
        Flyway.configure().dataSource(url,"phase3_test","").schemas(migrationSchema).locations("classpath:db/migration").target("18").load().migrate();
        try(var db=DriverManager.getConnection(url,"phase3_test","");var statement=db.createStatement()) {
            statement.execute("SET search_path TO "+migrationSchema+",public");
            statement.execute("insert into products(id,title) values('m1-preserve','M1')");
            statement.execute("insert into product_color_variants(product_id,color_id) values('m1-preserve','c')");
            statement.execute("insert into product_size_variants(color_variant_id,size_id,inventory) select id,v.k,v.n from product_color_variants cross join(values('zero',0),('positive',7),('unknown',null::int)) v(k,n)");
            stockBefore=scalar(statement,"select jsonb_agg(to_jsonb(s) order by id)::text from product_size_variants s");
        }
        Flyway.configure().dataSource(url,"phase3_test","").schemas(migrationSchema).locations("classpath:db/migration").load().migrate();
        try(var db=DriverManager.getConnection(url,"phase3_test","");var statement=db.createStatement()) {
            statement.execute("SET search_path TO "+migrationSchema+",public");
            assertEquals(stockBefore,scalar(statement,"select jsonb_agg(to_jsonb(s)-'inventory_revision' order by id)::text from product_size_variants s"));
            assertEquals("0",scalar(statement,"select count(*) from stock_movements"));
        }
        properties.add("spring.datasource.url", () -> url + "?currentSchema=" + SCHEMA + ",public");
        properties.add("spring.datasource.username", () -> "phase3_test"); properties.add("spring.datasource.password", () -> "");
        properties.add("spring.flyway.enabled", () -> "false"); properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        properties.add("spring.jpa.open-in-view", () -> "false");
        properties.add("app.vnpay.enabled", () -> "true"); properties.add("app.vnpay.hash-secret", () -> "sandbox-integration-test-secret");
        properties.add("app.jwt.secret", () -> "isolated-test-jwt-secret-32-characters");
        properties.add("spring.main.banner-mode", () -> "off");
    }

    @Test void migrationPreservesOldDataAndBackfillsAllCodStatesWithoutInventingPaidTime() {
        assertEquals(legacyOrders, sql.queryForObject("SELECT jsonb_agg(to_jsonb(o)-ARRAY['payment_method','payment_status','paid_at'] ORDER BY id)::text FROM orders o WHERE note='legacy-vnpay-test'", String.class));
        assertEquals(legacyItems, sql.queryForObject("SELECT jsonb_agg(to_jsonb(i)-'size_variant_id' ORDER BY id)::text FROM order_items i WHERE product_id='legacy-vnpay-product'", String.class));
        assertEquals(4, sql.queryForObject("SELECT count(*) FROM orders WHERE note='legacy-vnpay-test' AND payment_method='COD' AND payment_status=CASE status WHEN 'completed' THEN 'PAID' WHEN 'cancelled' THEN 'FAILED' ELSE 'PENDING' END AND paid_at IS NULL", Integer.class));
    }

    @Test void actualGuestCheckoutStoresTokenHashAndInitializesOneFixedUrlIncludingShipping() throws Exception {
        var fixture = fixture(false); String payload = checkoutBody(fixture.product(), "VNPAY");
        var checkout = send("POST", "/api/checkout", payload, null, null); assertEquals(200, checkout.statusCode());
        var data = JSON.readTree(checkout.body()); String ref = data.path("txnRef").asText(), token = data.path("paymentAccessToken").asText();
        assertTrue(ref.matches("[a-f0-9]{32}")); assertTrue(token.length() >= 40);
        assertEquals(256000, data.path("order").path("total").asInt()); assertEquals("PENDING", data.path("order").path("paymentStatus").asText());
        assertNotEquals(token, sql.queryForObject("SELECT access_token_hash FROM vnpay_payments WHERE txn_ref=?", String.class, ref));
        String body = "{\"orderId\":\"" + data.path("orderId").asText() + "\",\"amount\":1}";
        var first = send("POST", "/api/payments/vnpay/create", body, null, token); assertEquals(200, first.statusCode());
        String url = JSON.readTree(first.body()).path("paymentUrl").asText(); assertTrue(url.contains("vnp_Amount=25600000"));
        assertTrue(url.contains("vnp_Version=2.1.0"));
        assertEquals(first.body(), send("POST", "/api/payments/vnpay/create", body, null, token).body());
        assertEquals(403, send("GET", "/api/payments/vnpay/" + ref + "/status", null, null, null).statusCode());
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM vnpay_payments WHERE order_id=?", Integer.class, UUID.fromString(data.path("orderId").asText())));
    }

    @Test void defaultCodCheckoutAndCompletionStillWork() throws Exception {
        var f = fixture(false); var response = send("POST", "/api/checkout", checkoutBody(f.product(), null), null, null);
        assertEquals(200, response.statusCode()); var data = JSON.readTree(response.body());
        assertEquals("COD", data.path("order").path("paymentMethod").asText());
        var done = send("PUT", "/api/admin/orders/" + data.path("orderId").asText() + "/status", "{\"status\":\"completed\"}", admin(), null);
        assertEquals(200, done.statusCode()); assertEquals("PAID", JSON.readTree(done.body()).path("paymentStatus").asText());
    }

    @Test void validReturnAndRefreshNeverConfirmPaymentAndForgedReturnIsRejected() throws Exception {
        var f = fixture(false); var fields = callback(f, "00", "00");
        for (int i = 0; i < 3; i++) {
            var result = send("POST", "/api/payments/vnpay/verify-return", JSON.writeValueAsString(fields), null, f.token());
            assertEquals(200, result.statusCode()); assertEquals("SUCCESS", JSON.readTree(result.body()).path("gatewayOutcome").asText());
        }
        assertEquals("PENDING", state(f)); assertEquals(8, inventory(f)); assertEquals(0, historyCount(f));
        fields.put("vnp_ResponseCode", "24");
        assertEquals(400, send("POST", "/api/payments/vnpay/verify-return", JSON.writeValueAsString(fields), null, f.token()).statusCode());
    }

    @Test void publicIpnRejectsBadSignatureAmountUnknownRefAndDuplicateParametersWithoutMutations() throws Exception {
        var f = fixture(false); var fields = callback(f, "00", "00");
        fields.put("vnp_SecureHash", "0".repeat(128)); assertEquals("97", ipn(fields));
        fields = callback(f, "00", "00"); fields.put("vnp_Amount", "100"); sign(fields); assertEquals("04", ipn(fields));
        fields = callback(f, "00", "00"); fields.put("vnp_TxnRef", "missing"); sign(fields); assertEquals("01", ipn(fields));
        fields = callback(f, "00", "00"); fields.put("vnp_TmnCode", "ZZZZZZZZ"); sign(fields); assertEquals("99", ipn(fields));
        assertEquals("99", JSON.readTree(send("GET", "/api/payments/vnpay/ipn?" + query(callback(f, "00", "00")) + "&vnp_Amount=1", null, null, null).body()).path("RspCode").asText());
        assertEquals("PENDING", state(f)); assertEquals(8, inventory(f)); assertEquals(0, historyCount(f));
    }

    @Test void paidOrderCannotPayAgainButNormalCancellationPreservesPaidAndRestoresStockOnce() throws Exception {
        var f = fixture(false); String admin = admin(); var fields = callback(f, "00", "00");
        assertEquals("00", ipn(fields)); assertEquals("02", ipn(fields)); assertEquals("PAID", state(f));
        assertEquals(409, send("POST", "/api/payments/vnpay/create", "{\"orderId\":\"" + f.order() + "\"}", null, f.token()).statusCode());
        var cancel = send("PUT", "/api/admin/orders/" + f.order() + "/status", "{\"status\":\"cancelled\"}", admin, null);
        assertEquals(200, cancel.statusCode()); assertEquals("PAID", state(f)); assertEquals(10, inventory(f));
        assertEquals("02", ipn(fields)); assertEquals(10, inventory(f)); assertEquals(1, historyCount(f));
    }

    @Test void concurrentDuplicateFailureIpnCancelsAndRestoresInventoryExactlyOnce() throws Exception {
        var f = fixture(false); var fields = callback(f, "24", "02");
        List<String> codes = race(() -> ipn(fields), () -> ipn(fields));
        assertTrue(codes.contains("00")); assertTrue(codes.contains("02"));
        assertEquals("FAILED", state(f)); assertEquals("cancelled", orderState(f)); assertEquals(10, inventory(f)); assertEquals(1, historyCount(f));
    }

    @Test void fullOrderUpdateCanCancelPaidVnpayButCannotRewritePaymentFields() throws Exception {
        var f = fixture(false); assertEquals("00", ipn(callback(f, "00", "00")));
        var response = send("PUT", "/api/admin/orders/" + f.order(),
                "{\"status\":\"cancelled\",\"fullName\":\"Test\",\"phone\":\"0900000000\",\"note\":\"Cancel without implying refund\",\"paymentStatus\":\"FAILED\",\"paymentMethod\":\"COD\"}", admin(), null);
        assertEquals(200, response.statusCode()); assertEquals("cancelled", orderState(f));
        assertEquals("PAID", state(f)); assertEquals(10, inventory(f));
        assertEquals("VNPAY", JSON.readTree(response.body()).path("paymentMethod").asText());
    }

    @Test void expiredAndAmbiguousPaymentsKeepPendingAndReservedStock() throws Exception {
        var f = fixture(false);
        var status = send("GET", "/api/payments/vnpay/" + f.ref() + "/status", null, null, f.token());
        assertEquals(200, status.statusCode()); var body = JSON.readTree(status.body());
        assertTrue(body.path("expired").asBoolean()); assertTrue(body.path("requiresReview").asBoolean()); assertFalse(body.path("canPay").asBoolean());
        assertEquals(409, send("POST", "/api/payments/vnpay/create", "{\"orderId\":\"" + f.order() + "\"}", null, f.token()).statusCode());
        assertEquals("00", ipn(callback(f, "07", "07"))); assertEquals("PENDING", state(f)); assertEquals(8, inventory(f));
        assertEquals(409, send("PUT", "/api/admin/orders/" + f.order() + "/status", "{\"status\":\"shipping\"}", admin(), null).statusCode());
    }

    @Test void adminResolutionRequiresRoleSandboxCheckNoteAndImmutableFinalState() throws Exception {
        var f = fixture(false); String admin = admin();
        String path = "/api/admin/orders/" + f.order() + "/payment/resolve";
        assertTrue(send("POST", path, resolution("PAID", "Checked", true), null, null).statusCode() >= 400);
        assertEquals(403, send("POST", path, resolution("PAID", "Checked", true), member(), null).statusCode());
        assertEquals(400, send("POST", path, resolution("PAID", "   ", true), admin, null).statusCode());
        assertEquals(400, send("POST", path, resolution("PAID", "Checked", false), admin, null).statusCode());
        assertEquals(0, historyCount(f));
        assertEquals(200, send("POST", path, resolution("PAID", "Confirmed in Sandbox", true), admin, null).statusCode());
        assertEquals("PAID", state(f)); assertEquals(8, inventory(f));
        assertEquals(409, send("POST", path, resolution("FAILED", "Change conclusion", true), admin, null).statusCode());
        var details = JSON.readTree(send("GET", "/api/admin/orders/" + f.order() + "/payment", null, admin, null).body());
        assertEquals("ADMIN", details.path("history").get(0).path("source").asText());
        assertTrue(details.path("history").get(0).path("actorName").asText().startsWith("admin-"));
        assertFalse(details.path("history").get(0).path("actorId").asText().isBlank());
        assertEquals("00", ipn(callback(f, "24", "02"))); assertEquals("PAID", state(f)); assertEquals(8, inventory(f));
        assertTrue(JSON.readTree(send("GET", "/api/admin/orders/" + f.order() + "/payment", null, admin, null).body()).path("payment").path("requiresReview").asBoolean());
    }

    @Test void concurrentAdminFailureAndSuccessIpnHaveOneFinalTransitionAndKeepConflictEvidence() throws Exception {
        var f = fixture(false); String admin = admin();
        race(() -> ipn(callback(f, "00", "00")), () -> Integer.toString(send("POST", "/api/admin/orders/" + f.order() + "/payment/resolve", resolution("FAILED", "Sandbox final failure", true), admin, null).statusCode()));
        assertTrue(Set.of("PAID", "FAILED").contains(state(f)));
        assertEquals("PAID".equals(state(f)) ? 8 : 10, inventory(f));
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM payment_history WHERE payment_id=? AND previous_status<>new_status", Integer.class, f.payment()));
        assertEquals("02", ipn(callback(f, "00", "00"))); // either already paid or the conflict evidence is already recorded
    }

    @Test void lateSuccessAfterOrdinaryPendingCancellationRequiresReviewWithoutReopeningOrder() throws Exception {
        var f = fixture(false); String admin = admin();
        sql.update("UPDATE vnpay_payments SET expires_at=now()+interval '15 minutes',review_after=now()+interval '20 minutes' WHERE id=?", f.payment());
        assertEquals(200, send("PUT", "/api/admin/orders/" + f.order() + "/status", "{\"status\":\"cancelled\"}", admin, null).statusCode());
        assertTrue(JSON.readTree(send("GET", "/api/payments/vnpay/" + f.ref() + "/status", null, null, f.token()).body()).path("requiresReview").asBoolean());
        assertEquals("00", ipn(callback(f, "00", "00"))); assertEquals("PENDING", state(f)); assertEquals("cancelled", orderState(f)); assertEquals(10, inventory(f));
        assertEquals(200, send("POST", "/api/admin/orders/" + f.order() + "/payment/resolve", resolution("PAID", "Late success confirmed on Sandbox", true), admin, null).statusCode());
        assertEquals("PAID", state(f)); assertEquals("cancelled", orderState(f)); assertEquals(10, inventory(f));
    }

    @Test void userCannotReadAnotherUsersOrderOrResolveTooEarly() throws Exception {
        var f = fixture(true);
        assertEquals(404, send("GET", "/api/orders/" + f.order(), null, member(), null).statusCode());
        sql.update("UPDATE vnpay_payments SET expires_at=now()+interval '15 minutes',review_after=now()+interval '20 minutes' WHERE id=?", f.payment());
        assertEquals(409, send("POST", "/api/admin/orders/" + f.order() + "/payment/resolve", resolution("FAILED", "Too early", true), admin(), null).statusCode());
    }

    @Test void personalizationReadsRealProfileViewsCartAndCompletedOrdersAndCheckoutClearsServerCart() throws Exception {
        var source = fixture(false); var match = fixture(false);
        String category = "personalization-" + UUID.randomUUID();
        sql.update("UPDATE products SET category_slug=?,type=?,tags='[\"polo\"]'::jsonb WHERE id IN (?,?)",
                category, category, source.product(), match.product());
        sql.update("UPDATE products SET sold_count=1 WHERE id=?", match.product());
        UUID owner = actor("USER"); String bearer = jwt.generateAccessToken(users.findById(owner).orElseThrow());
        assertEquals(200, send("PUT", "/api/users/me", "{\"preferredCategories\":[\"" + category
                + "\"],\"priceMin\":100000,\"priceMax\":200000}", bearer, null).statusCode());
        String body = "{\"productId\":\"" + source.product() + "\"}";
        assertEquals(403, send("POST", "/api/users/me/product-views", body, null, null).statusCode());
        assertEquals(204, send("POST", "/api/users/me/product-views", body, bearer, null).statusCode());
        assertEquals(204, send("POST", "/api/users/me/product-views", body, bearer, null).statusCode());
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM user_product_views WHERE user_id=?", Integer.class, owner));
        sql.update("UPDATE orders SET user_id=?,status='completed',payment_status='PAID',created_at=now()-interval '31 days' WHERE id=?",
                owner, source.order());
        String cartItem = "{\"productId\":\"" + source.product() + "\",\"colorId\":\"black\",\"sizeId\":\"M\",\"quantity\":2}";
        assertEquals(200, send("POST", "/api/cart?clientId=ignored-guest", cartItem, bearer, null).statusCode());
        var response = send("GET", "/api/recommendations?size=12", null, bearer, null);
        assertEquals(200, response.statusCode());
        var items = JSON.readTree(response.body()); assertTrue(items.isArray());
        assertEquals(match.product(), items.get(0).path("id").asText());
        for (var item : items) assertNotEquals(source.product(), item.path("id").asText());
        assertEquals(200, send("GET", "/api/recommendations?size=12", null, null, null).statusCode());
        assertEquals(200, send("POST", "/api/checkout", checkoutBody(source.product(), "COD"), bearer, null).statusCode());
        assertEquals("[]", send("GET", "/api/cart?clientId=ignored-guest", null, bearer, null).body());
    }

    @Autowired InventoryService inventoryService;
    @Autowired AdminProductService adminProducts;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    void transaction(Runnable action) {
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s -> action.run());
    }
    void delta(Fixture f, String key, int amount) {
        transaction(() -> {
            inventoryService.lockProducts(List.of(f.product()));
            var sizes = inventoryService.lockSizes(List.of(f.sizeId()));
            inventoryService.change(sizes.get(f.sizeId()), key, "TEST", amount);
        });
    }
    void assertLedger(Fixture f, int initial) {
        assertEquals(initial + sql.queryForObject("select coalesce(sum(delta),0)::int from stock_movements where size_variant_id=?", Integer.class,f.sizeId()), inventory(f));
        assertEquals(sql.queryForObject("select count(*) from stock_movements where size_variant_id=?", Long.class,f.sizeId()),
            sql.queryForObject("select inventory_revision from product_size_variants where id=?",Long.class,f.sizeId()));
    }
    @Test void lastSizeConcurrentCodAndVnpayHaveExactlyOneWinner() throws Exception {
        for (String method : List.of("COD", "VNPAY")) {
            var f=fixture(false); sql.update("update product_size_variants set inventory=1 where id=?",f.sizeId());
            String body=checkoutBody(f.product(),method).replace("256000","131000").replace("\"quantity\":2","\"quantity\":1");
            var responses=race(() -> send("POST","/api/checkout",body,null,null), () -> send("POST","/api/checkout",body,null,null));
            assertEquals(1,responses.stream().filter(r -> r.statusCode()==200).count(), responses.toString());
            assertEquals(0,inventory(f)); assertLedger(f,1);
        }
    }
    @Test void bothCheckoutsReadBeforeProductLockIsReleased() throws Exception {
        var f=fixture(false); sql.update("update product_size_variants set inventory=1 where id=?",f.sizeId());
        String body=checkoutBody(f.product(),"COD").replace("256000","131000").replace("\"quantity\":2","\"quantity\":1");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var holder=pool.submit(() -> transaction(() -> {
                inventoryService.lockProducts(List.of(f.product())); held.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); }
                catch(InterruptedException ex) { throw new RuntimeException(ex); }
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            var a=pool.submit(() -> send("POST","/api/checkout",body,null,null));
            var b=pool.submit(() -> send("POST","/api/checkout",body,null,null));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); boolean waiting=false;
            while(System.nanoTime()<end) {
                int n=sql.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Integer.class);
                if(n>=2) { waiting=true;break; } Thread.sleep(20);
            }
            assertTrue(waiting,"Both real transactions must wait for product lock");
            release.countDown(); holder.get(5,TimeUnit.SECONDS);
            var codes=List.of(a.get(10,TimeUnit.SECONDS).statusCode(),b.get(10,TimeUnit.SECONDS).statusCode());
            assertEquals(1,codes.stream().filter(c -> c==200).count());assertEquals(0,inventory(f));assertLedger(f,1);
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void appendOnlyLedgerAndUnknownStockFailClosed() {
        var f=fixture(false);delta(f,"audit",1);
        assertThrows(org.springframework.dao.DataAccessException.class,() -> sql.update("delete from stock_movements where size_variant_id=?",f.sizeId()));
        sql.update("update product_size_variants set inventory=null where id=?",f.sizeId());
        assertThrows(IllegalArgumentException.class,() -> delta(f,"unknown",1));
        assertEquals(1,sql.queryForObject("select count(*) from stock_movements where size_variant_id=?",Integer.class,f.sizeId()));
    }
    @Test void distinctLegacyOrdersRestoreConcurrentlyWithoutLostUpdate() throws Exception {
        var f=fixture(false); UUID second=UUID.randomUUID();
        sql.update("insert into orders(id,status,total,payment_method,payment_status) values(?,'processing',250000,'COD','PENDING')",second);
        sql.update("insert into order_items(order_id,product_id,quantity,price_snapshot,color_id,size_id) values(?,?,3,125000,'black','M')",second,f.product());
        String bearer=admin();
        var results=race(() -> send("PUT","/api/admin/orders/"+f.order()+"/status","{\"status\":\"cancelled\"}",bearer,null),
            () -> send("PUT","/api/admin/orders/"+second+"/status","{\"status\":\"cancelled\"}",bearer,null));
        results.forEach(r -> assertEquals(200,r.statusCode(),r.body()));
        assertEquals(13,inventory(f)); assertLedger(f,8);
    }
    @Test void staleAdminCannotOverwriteCheckoutAndVariantKeysStayStable() throws Exception {
        var f=fixture(false); var dto=adminProducts.getProduct(f.product());
        assertEquals(200,send("POST","/api/checkout",checkoutBody(f.product(),"COD"),null,null).statusCode());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> adminProducts.update(f.product(),dto));
        assertEquals(6,inventory(f));
        var fresh=adminProducts.getProduct(f.product()); fresh.setTitle("Edited safely");
        adminProducts.update(f.product(),fresh);
        assertEquals(f.sizeId(),sql.queryForObject("select id from product_size_variants where color_variant_id in(select id from product_color_variants where product_id=?)",Long.class,f.product()));
        assertEquals(6,inventory(f)); assertLedger(f,8);
    }
    @Test void actualAdminAdjustmentRacesCheckoutWithoutOverwritingDebit() throws Exception {
        var f=fixture(false);var dto=adminProducts.getProduct(f.product());
        dto.getColors().get(0).getSizes().get(0).setInventory(11);
        var result=race(() -> {
            try {adminProducts.update(f.product(),dto);return "adjusted";}
            catch(org.springframework.web.server.ResponseStatusException conflict) {assertEquals(409,conflict.getStatusCode().value());return "conflict";}
        }, () -> {
            assertEquals(200,send("POST","/api/checkout",checkoutBody(f.product(),"COD"),null,null).statusCode());return "ordered";
        });
        assertEquals(result.contains("adjusted")?9:6,inventory(f));assertLedger(f,8);
    }
    @Test void adminCreateInitializesThroughLedgerAndMetadataEditDoesNotReinitialize() {
        var source=fixture(false);var dto=adminProducts.getProduct(source.product());dto.setId("admin-"+UUID.randomUUID());
        var created=adminProducts.create(dto);
        long size=sql.queryForObject("select s.id from product_size_variants s join product_color_variants c on c.id=s.color_variant_id where c.product_id=?",Long.class,created.getId());
        assertEquals(1,sql.queryForObject("select count(*) from stock_movements where size_variant_id=?",Integer.class,size));
        var edit=adminProducts.getProduct(created.getId());edit.setTitle("Metadata only");adminProducts.update(created.getId(),edit);
        assertEquals(8,sql.queryForObject("select inventory from product_size_variants where id=?",Integer.class,size));
        assertEquals(1,sql.queryForObject("select count(*) from stock_movements where size_variant_id=?",Integer.class,size));
    }
    @Test void checkoutRestoreAndAdminDeltaAreSerialized() throws Exception {
        var f=fixture(false); String bearer=admin();
        var results=race(() -> { delta(f,"admin-delta",3); return "adjusted"; }, () -> {
            assertEquals(200,send("POST","/api/checkout",checkoutBody(f.product(),"COD"),null,null).statusCode());
            assertEquals(200,send("PUT","/api/admin/orders/"+f.order()+"/status","{\"status\":\"cancelled\"}",bearer,null).statusCode()); return "ordered";
        });
        assertEquals(11,inventory(f)); assertLedger(f,8);
    }
    @Test void stockCommandsAreIdempotentAndRollbackLedgerAndInventoryTogether() throws Exception {
        var f=fixture(false);
        race(() -> { delta(f,"same-operation",2);return 1;}, () -> {delta(f,"same-operation",2);return 1;});
        assertEquals(10,inventory(f)); assertLedger(f,8);
        assertThrows(IllegalStateException.class, () -> transaction(() -> {
            inventoryService.lockProducts(List.of(f.product()));
            var size=inventoryService.lockSizes(List.of(f.sizeId())).get(f.sizeId());
            inventoryService.change(size,"rolled-back","TEST",3);
            throw new IllegalStateException("fail after ledger insert");
        }));
        assertEquals(10,inventory(f)); assertLedger(f,8);
        assertThrows(IllegalArgumentException.class, () -> delta(f,"oversell",-11));
        assertLedger(f,8);
    }
    @Test void missingLegacyMappingRollsBackCancelInsteadOfSilentlyLosingRestore() throws Exception {
        var f=fixture(false); sql.update("update order_items set size_id='missing' where order_id=?",f.order());
        var response=send("PUT","/api/admin/orders/"+f.order()+"/status","{\"status\":\"cancelled\"}",admin(),null);
        assertTrue(response.statusCode()>=400); assertEquals("processing",orderState(f));assertEquals(8,inventory(f));assertLedger(f,8);
    }

    record Fixture(UUID order, UUID payment, String ref, String token, String product, long sizeId) {}
    Fixture fixture(boolean owned) {
        UUID order = UUID.randomUUID(), payment = UUID.randomUUID(); String ref = UUID.randomUUID().toString().replace("-", ""), product = "p-" + ref, token = "guest-" + ref;
        sql.update("INSERT INTO products(id,title,price,sold_count) VALUES(?, 'Test',125000,0)", product);
        Long color = sql.queryForObject("INSERT INTO product_color_variants(product_id,color_id,color_name) VALUES(?,'black','Black') RETURNING id", Long.class, product);
        Long size = sql.queryForObject("INSERT INTO product_size_variants(color_variant_id,size_id,size_name,inventory,price) VALUES(?,'M','M',8,125000) RETURNING id", Long.class, color);
        UUID owner = owned ? actor("USER") : null;
        sql.update("INSERT INTO orders(id,user_id,status,total,payment_method,payment_status,created_at,updated_at) VALUES(?,?,'processing',256000,'VNPAY','PENDING',now(),now())", order, owner);
        sql.update("INSERT INTO order_items(order_id,product_id,quantity,price_snapshot,color_id,color_snapshot,size_id,size_snapshot) VALUES(?,?,2,125000,'black','Black','M','M')", order, product);
        sql.update("INSERT INTO vnpay_payments(id,order_id,txn_ref,amount,access_token_hash,created_at,expires_at,review_after) VALUES(?,?,?,256000,?,now()-interval '30 minutes',now()-interval '15 minutes',now()-interval '10 minutes')", payment, order, ref, PaymentService.tokenHash(token));
        return new Fixture(order, payment, ref, token, product, size);
    }
    String checkoutBody(String product, String method) {
        return "{\"name\":\"Test Guest\",\"phone\":\"0900000000\",\"expectedShippingFee\":6000,\"expectedTotal\":256000,\"deliveryAddress\":{},\"items\":[{\"productId\":\"" + product + "\",\"colorId\":\"black\",\"sizeId\":\"M\",\"quantity\":2}]" + (method == null ? "" : ",\"paymentMethod\":\"" + method + "\"") + "}";
    }
    UUID actor(String role) {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO users(id,email,password_hash,enabled) VALUES(?,?,NULL,true)", id, ("ADMIN".equals(role) ? "admin-" : "member-") + id + "@example.invalid");
        sql.update("INSERT INTO roles(name) VALUES(?) ON CONFLICT(name) DO NOTHING", role);
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?", id, role); return id;
    }
    String admin() { return jwt.generateAccessToken(users.findById(actor("ADMIN")).orElseThrow()); }
    String member() { return jwt.generateAccessToken(users.findById(actor("USER")).orElseThrow()); }
    String resolution(String status, String note, boolean checked) throws Exception { return JSON.writeValueAsString(Map.of("status", status, "note", note, "sandboxChecked", checked, "actorId", UUID.randomUUID().toString())); }
    Map<String, String> callback(Fixture f, String code, String status) {
        var fields = new HashMap<>(Map.of("vnp_TmnCode", "PBHJI61M", "vnp_TxnRef", f.ref(), "vnp_Amount", "25600000", "vnp_ResponseCode", code,
                "vnp_TransactionStatus", status, "vnp_TransactionNo", "12345678", "vnp_BankCode", "NCB", "vnp_PayDate", "20261022220000", "vnp_OrderInfo", "Thanh toan don hang"));
        sign(fields); return fields;
    }
    void sign(Map<String, String> fields) { fields.put("vnp_SecureHash", gateway.hmac(gateway.canonical(fields))); }
    String query(Map<String, String> fields) { return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&")); }
    String ipn(Map<String, String> fields) throws Exception { return JSON.readTree(send("GET", "/api/payments/vnpay/ipn?" + query(fields), null, null, null).body()).path("RspCode").asText(); }
    HttpResponse<String> send(String method, String path, String body, String bearer, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (token != null) request.header("X-Payment-Token", token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    String state(Fixture f) { return sql.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, f.order()); }
    String orderState(Fixture f) { return sql.queryForObject("SELECT status FROM orders WHERE id=?", String.class, f.order()); }
    int inventory(Fixture f) { return sql.queryForObject("SELECT inventory FROM product_size_variants WHERE id=?", Integer.class, f.sizeId()); }
    int historyCount(Fixture f) { return sql.queryForObject("SELECT count(*) FROM payment_history WHERE payment_id=?", Integer.class, f.payment()); }
    <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Future<T> a = pool.submit(() -> { start.await(); return first.call(); }), b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown(); return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    static String scalar(Statement s, String query) throws SQLException { try (var r = s.executeQuery(query)) { r.next(); return r.getString(1); } }
}
