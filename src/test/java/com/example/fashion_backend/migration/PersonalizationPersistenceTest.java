package com.example.fashion_backend.migration;

import com.example.fashion_backend.repository.UserProductViewRepository;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Only uses the dedicated test cluster, never the application's database. */
@EnabledIfEnvironmentVariable(named = "PHASE3_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55439/phase3_test")
class PersonalizationPersistenceTest {
    @Test void migrationAndRealRepositoryUpsertAreUniqueThrottledAndUserScoped() throws Exception {
        String url = System.getenv("PHASE3_TEST_DB_URL");
        String schema = "personalization_" + UUID.randomUUID().toString().replace("-", "");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "phase3_test", ""); var sql = connection.createStatement()) {
            sql.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public");
            sql.execute("ALTER EXTENSION pg_trgm SET SCHEMA public");
        }
        Flyway.configure().dataSource(url, "phase3_test", "").schemas(schema).target("17").load().migrate();
        try (var connection = DriverManager.getConnection(url, "phase3_test", ""); var sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + schema + ",public");
            sql.execute("INSERT INTO users(id,email) VALUES ('" + first + "','a@example.test'),('" + second + "','b@example.test')");
            sql.execute("INSERT INTO products(id,title,sold_count) VALUES ('p1','Test',12)");
        }
        Flyway.configure().dataSource(url, "phase3_test", "").schemas(schema).load().migrate();
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", "phase3_test", ""));
        factory.setPackagesToScan("com.example.fashion_backend.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        factory.afterPropertiesSet();
        try (var em = factory.getObject().createEntityManager()) {
            var repo = new JpaRepositoryFactory(em).getRepository(UserProductViewRepository.class);
            OffsetDateTime now = OffsetDateTime.parse("2026-10-23T00:00:00Z");
            em.getTransaction().begin();
            assertEquals(1, repo.recordView(first, "p1", now, now.minusMinutes(30)));
            assertEquals(0, repo.recordView(first, "p1", now.plusMinutes(5), now.minusMinutes(25)));
            assertEquals(1, repo.recordView(first, "p1", now.plusMinutes(31), now.plusMinutes(1)));
            assertEquals(1, repo.recordView(second, "p1", now, now.minusMinutes(30)));
            em.getTransaction().commit();
            var own = repo.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(first, now.minusDays(30));
            assertEquals(1, own.size());
            assertEquals(now.plusMinutes(31).toInstant(), own.get(0).getLastViewedAt().toInstant());
            assertEquals(first, own.get(0).getUserId());
            assertEquals(0, repo.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(first, now.plusDays(1)).size());
            assertEquals(2, repo.count());
            assertEquals(12, ((Number) em.createNativeQuery("SELECT sold_count FROM products WHERE id='p1'", Integer.class).getSingleResult()).intValue());

            // Exercise the actual JPA cart mappings and login merge, including persistence/clear.
            var repositories = new JpaRepositoryFactory(em);
            var cartRepo = repositories.getRepository(com.example.fashion_backend.repository.CartRepository.class);
            var productRepo = repositories.getRepository(com.example.fashion_backend.repository.ProductRepository.class);
            var carts = new com.example.fashion_backend.service.CartService(cartRepo, productRepo);
            var item = new com.example.fashion_backend.model.CartItem("p1", 2);
            em.getTransaction().begin();
            carts.addToCart("browser", item);
            em.flush(); em.clear();
            assertEquals(2, carts.getCartItems("browser").get(0).getQuantity());
            carts.mergeGuestCartToUser(first, "browser");
            em.flush(); em.clear();
            assertTrue(carts.getCartItems("browser").isEmpty());
            assertEquals(2, carts.getCartItems(first, "browser").get(0).getQuantity());
            carts.addToCart(first, "another-device", item);
            em.flush(); em.clear();
            assertEquals(4, carts.getCartItems(first, null).get(0).getQuantity());
            assertTrue(carts.getCartItems(second, "browser").isEmpty());
            carts.removePurchasedItems(first, "browser", List.of(item));
            em.flush(); em.clear();
            assertEquals(2, carts.getCartItems(first, null).get(0).getQuantity());
            carts.removeItem(first, null, "p1");
            em.flush(); em.clear();
            assertTrue(carts.getCartItems(first, null).isEmpty());
            em.getTransaction().commit();
        } finally { factory.destroy(); }
    }
}
