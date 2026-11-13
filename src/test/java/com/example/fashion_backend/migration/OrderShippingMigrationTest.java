package com.example.fashion_backend.migration;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PHASE3_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55439/phase3_test")
class OrderShippingMigrationTest {
    @Test void preservesEveryLegacyFieldAndInitializesFreeShipping() throws Exception {
        String url = System.getenv("PHASE3_TEST_DB_URL");
        // pg_trgm is database-wide: keep it visible across repeated isolated test schemas.
        try (var db = DriverManager.getConnection(url, "phase3_test", ""); var setup = db.createStatement()) {
            setup.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public");
            setup.execute("ALTER EXTENSION pg_trgm SET SCHEMA public");
        }
        String schema = "shipping_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(url, "phase3_test", "").schemas(schema).locations("classpath:db/migration").target("11").load().migrate();
        try (Connection db = DriverManager.getConnection(url, "phase3_test", ""); Statement sql = db.createStatement()) {
            sql.execute("SET search_path TO " + schema + ", public");
            sql.execute("""
                INSERT INTO orders(id,status,total,address_line,city,note,created_at,updated_at)
                SELECT gen_random_uuid(), status, CASE WHEN status='cancelled' THEN NULL ELSE 123456.78 END,
                       'Địa chỉ cũ', 'Hà Nội', 'Ghi chú cũ', '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z'
                FROM unnest(ARRAY['processing','shipping','completed','cancelled']) status
                """);
            sql.execute("UPDATE orders SET delivery_formatted_address='Phase 3 address',delivery_latitude=21,delivery_longitude=105,delivery_place_id='id',delivery_address_source='GOGODUK' WHERE status='processing'");
            sql.execute("INSERT INTO products(id,title) VALUES ('test-product','Test')");
            sql.execute("INSERT INTO order_items(order_id,product_id,quantity,price_snapshot) SELECT id,'test-product',2,999 FROM orders");
            String oldOrders = scalar(sql, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM orders o");
            String oldItems = scalar(sql, "SELECT jsonb_agg(to_jsonb(i) ORDER BY id)::text FROM order_items i");
            // Simulate a dev database where ddl-auto already added one column without defaults.
            sql.execute("ALTER TABLE orders ADD COLUMN shipping_fee numeric(12,2)");
            Flyway.configure().dataSource(url, "phase3_test", "").schemas(schema).locations("classpath:db/migration").target("12").load().migrate();
            assertEquals(oldOrders, scalar(sql, "SELECT jsonb_agg(to_jsonb(o) - ARRAY['items_subtotal','shipping_fee','shipping_distance_meters','shipping_provider','shipping_vehicle','shipping_calculated_at'] ORDER BY id)::text FROM orders o"));
            assertEquals(oldItems, scalar(sql, "SELECT jsonb_agg(to_jsonb(i) ORDER BY id)::text FROM order_items i"));
            assertEquals("4", scalar(sql, "SELECT count(*) FROM orders WHERE shipping_fee=0 AND shipping_distance_meters IS NULL AND items_subtotal IS NULL"));
            assertFailure(sql, "UPDATE orders SET shipping_fee=3000");
            sql.execute("""
                INSERT INTO orders(id,status,total,items_subtotal,shipping_fee,shipping_distance_meters,shipping_provider,shipping_vehicle,shipping_calculated_at,
                                   delivery_formatted_address,delivery_latitude,delivery_longitude,delivery_place_id,delivery_address_source)
                VALUES(gen_random_uuid(),'processing',256000,250000,6000,6000.01,'GOGODUK','motobike',now(),'Address',21,105,'id','GOGODUK')
                """);
            assertFailure(sql, "UPDATE orders SET total=250000 WHERE shipping_provider='GOGODUK'");
            assertFailure(sql, "UPDATE orders SET shipping_distance_meters='NaN' WHERE shipping_provider='GOGODUK'");
            assertFailure(sql, "UPDATE orders SET shipping_distance_meters=5000 WHERE shipping_provider='GOGODUK'");
            assertFailure(sql, "UPDATE orders SET shipping_provider=NULL WHERE shipping_vehicle='motobike'");
        }
    }

    private void assertFailure(Statement sql, String query) {
        assertEquals("23514", assertThrows(SQLException.class, () -> sql.execute(query)).getSQLState());
    }
    private String scalar(Statement sql, String query) throws SQLException {
        try (var result = sql.executeQuery(query)) { assertTrue(result.next()); return result.getString(1); }
    }
}
