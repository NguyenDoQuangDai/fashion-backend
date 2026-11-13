package com.example.fashion_backend.migration;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only on the dedicated local test cluster, never on the application database. */
@EnabledIfEnvironmentVariable(named = "PHASE3_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55439/phase3_test")
class OrderAddressMigrationTest {
    @Test void migrationPreservesLegacyRowsAndEnforcesCompleteSnapshots() throws Exception {
        String url = System.getenv("PHASE3_TEST_DB_URL");
        // pg_trgm is database-wide: keep it visible across repeated isolated test schemas.
        try (var db = DriverManager.getConnection(url, "phase3_test", ""); var setup = db.createStatement()) {
            setup.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public");
            setup.execute("ALTER EXTENSION pg_trgm SET SCHEMA public");
        }
        String schema = "phase3_" + UUID.randomUUID().toString().replace("-", "");
        var before = Flyway.configure().dataSource(url, "phase3_test", "").schemas(schema)
                .locations("classpath:db/migration").target("10").load();
        before.migrate();
        try (Connection connection = DriverManager.getConnection(url, "phase3_test", ""); Statement sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + schema + ", public");
            sql.execute("""
                INSERT INTO orders(id,status,total,address_line,city,note,created_at,updated_at)
                SELECT gen_random_uuid(), status, CASE WHEN status='cancelled' THEN NULL ELSE 123456.78 END,
                       'Địa chỉ cũ', CASE WHEN status='processing' THEN NULL ELSE 'Hà Nội' END,
                       'Giữ nguyên ghi chú', '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z'
                FROM unnest(ARRAY['processing','shipping','completed','cancelled']) status
                """);
            sql.execute("INSERT INTO products(id,title) VALUES ('test-product','Áo nam')");
            sql.execute("INSERT INTO order_items(order_id,product_id,title_snapshot,quantity,price_snapshot) SELECT id,'test-product','Tên lịch sử',2,999 FROM orders");
            String oldOrders = scalar(sql, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM orders o");
            String oldItems = scalar(sql, "SELECT jsonb_agg(to_jsonb(i) ORDER BY id)::text FROM order_items i");
            Flyway.configure().dataSource(url, "phase3_test", "").schemas(schema).locations("classpath:db/migration").target("11").load().migrate();
            assertEquals(oldOrders, scalar(sql, """
                SELECT jsonb_agg(to_jsonb(o) - ARRAY['delivery_formatted_address','delivery_latitude',
                'delivery_longitude','delivery_place_id','delivery_address_source'] ORDER BY id)::text FROM orders o
                """));
            assertEquals(oldItems, scalar(sql, "SELECT jsonb_agg(to_jsonb(i) ORDER BY id)::text FROM order_items i"));
            assertEquals("4", scalar(sql, "SELECT count(*) FROM orders WHERE delivery_latitude IS NULL AND delivery_longitude IS NULL AND delivery_place_id IS NULL AND delivery_formatted_address IS NULL AND delivery_address_source IS NULL"));
            assertConstraintFailure(sql, "UPDATE orders SET delivery_latitude=21");
            assertConstraintFailure(sql, "UPDATE orders SET delivery_formatted_address='Address',delivery_latitude=21,delivery_longitude=105,delivery_place_id='id'");
            assertConstraintFailure(sql, "UPDATE orders SET delivery_formatted_address='Address',delivery_latitude=91,delivery_longitude=105,delivery_place_id='id',delivery_address_source='GOGODUK'");
            assertConstraintFailure(sql, "UPDATE orders SET delivery_formatted_address='Address',delivery_latitude='NaN',delivery_longitude=105,delivery_place_id='id',delivery_address_source='GOGODUK'");
            sql.execute("INSERT INTO orders(id,status,total,delivery_formatted_address,delivery_latitude,delivery_longitude,delivery_place_id,delivery_address_source) VALUES(gen_random_uuid(),'processing',100,'Hà Nội',21.0288654,105.7819884,'place-id','GOGODUK')");
            assertEquals("21.0288654", scalar(sql, "SELECT delivery_latitude FROM orders WHERE delivery_address_source='GOGODUK'"));
        }
    }

    private static void assertConstraintFailure(Statement sql, String update) {
        SQLException exception = assertThrows(SQLException.class, () -> sql.execute(update));
        assertEquals("23514", exception.getSQLState());
    }

    private static String scalar(Statement sql, String query) throws SQLException {
        try (ResultSet result = sql.executeQuery(query)) { assertTrue(result.next()); return result.getString(1); }
    }
}
