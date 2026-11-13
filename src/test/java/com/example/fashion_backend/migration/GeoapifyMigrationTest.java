package com.example.fashion_backend.migration;

import com.example.fashion_backend.service.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PHASE3_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55439/phase3_test")
class GeoapifyMigrationTest {
    @Configuration @EnableTransactionManagement static class TxConfig {}
    @Test void migrationPreservesHistoryAndEnforcesIndependentSourcesAndVehicles() throws Exception {
        String url=System.getenv("PHASE3_TEST_DB_URL"),schema="geo_"+UUID.randomUUID().toString().replace("-","");
        try(var db=DriverManager.getConnection(url,"phase3_test","");var sql=db.createStatement()) {
            sql.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public");
        }
        migrate(url,schema,"16");
        try(var db=DriverManager.getConnection(url,"phase3_test","");var sql=db.createStatement()) {
            sql.execute("SET search_path TO "+schema+",public");
            sql.execute("INSERT INTO orders(id,status,total,address_line,city,note) VALUES(gen_random_uuid(),'processing',12345,'Địa chỉ cũ','Hà Nội','Ghi chú')");
            insert(sql,"GOGODUK","GOGODUK","motobike");
            String before=scalar(sql,"SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM orders o");
            migrate(url,schema,"17");
            assertEquals(before,scalar(sql,"SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM orders o"));
            insert(sql,"GEOAPIFY","GEOAPIFY","scooter");
            insert(sql,"GOGODUK","GEOAPIFY","scooter");
            insert(sql,"GEOAPIFY","GOGODUK","motobike");
            assertThrows(SQLException.class,()->insert(sql,"GEOAPIFY","GEOAPIFY","drive"));
            assertThrows(SQLException.class,()->insert(sql,"UNKNOWN","GEOAPIFY","scooter"));
            assertThrows(SQLException.class,()->sql.execute("UPDATE orders SET shipping_fee=0 WHERE shipping_provider='GEOAPIFY'"));
            assertThrows(SQLException.class,()->sql.execute("UPDATE orders SET shipping_provider=NULL WHERE shipping_provider='GEOAPIFY'"));
            assertThrows(SQLException.class,()->sql.execute("UPDATE orders SET shipping_distance_meters='NaN' WHERE shipping_provider='GEOAPIFY'"));
            sql.execute("UPDATE orders SET shipping_fee_original=shipping_fee,shipping_fee=1000,shipping_fee_adjusted_at=now(),total=items_subtotal+1000 WHERE shipping_provider='GEOAPIFY'");
            verifySharedStateInsideReadOnlyCheckout(url,schema);
        } finally {
            try(var db=DriverManager.getConnection(url,"phase3_test","");var sql=db.createStatement()) {sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private void verifySharedStateInsideReadOnlyCheckout(String url,String schema) throws Exception {
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema+",public","phase3_test","");
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class,()->source);
            context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(source));
            context.registerBean(PlatformTransactionManager.class,()->{
                var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);return manager;
            });
            context.register(TxConfig.class,JdbcFallbackStore.class);context.refresh();
            var store=context.getBean(FallbackStore.class);
            var transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));transaction.setReadOnly(true);
            transaction.execute(status->{
                store.put("test","snapshot",java.time.Duration.ofMinutes(1));
                assertEquals("snapshot",store.get("test"));
                assertTrue(store.acquire("shared",1,60000));assertFalse(store.acquire("shared",1,60000));return null;
            });
            ExecutorService pool=Executors.newFixedThreadPool(8);
            try {
                var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<20;i++)tasks.add(()->store.acquire("concurrent",4,86400000));
                long accepted=0;for(var result:pool.invokeAll(tasks))if(result.get())accepted++;
                assertEquals(4,accepted);
            } finally {pool.shutdownNow();}
            store.put("expired","gone",java.time.Duration.ofSeconds(-1));assertNull(store.get("expired"));
        }
    }
    private void migrate(String url,String schema,String target) {
        Flyway.configure().dataSource(url,"phase3_test","").schemas(schema).locations("classpath:db/migration").target(target).load().migrate();
    }
    private String scalar(Statement sql,String query) throws SQLException {try(var rs=sql.executeQuery(query)){rs.next();return rs.getString(1);}}
    private void insert(Statement sql,String addressSource,String provider,String vehicle) throws SQLException {
        sql.execute("""
            INSERT INTO orders(id,status,total,items_subtotal,shipping_fee,shipping_distance_meters,shipping_provider,shipping_vehicle,
              shipping_calculated_at,shipping_route_status,delivery_formatted_address,delivery_latitude,delivery_longitude,delivery_place_id,delivery_address_source)
            VALUES(gen_random_uuid(),'processing',106000,100000,6000,6000.01,'%s','%s',now(),'OK','Address',21,105,'reference','%s')
            """.formatted(provider,vehicle,addressSource));
    }
}
