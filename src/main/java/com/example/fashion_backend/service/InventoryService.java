package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.*;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** All stock writers: order -> products sorted -> sizes sorted. Never merge detached catalog graphs. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryService {
    private final EntityManager em;
    private final JdbcTemplate sql;
    public InventoryService(EntityManager em, JdbcTemplate sql) { this.em = em; this.sql = sql; }

    public void persistNew(ProductEntity product) {
        // persist (not merge) preserves child identities and never upserts an existing product.
        em.persist(product); em.flush();
    }
    public void lockProducts(Collection<String> ids) {
        sql.execute("SET LOCAL lock_timeout = '5s'");
        for (String id : new TreeSet<>(ids)) {
            ProductEntity p = em.find(ProductEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (p == null) throw new IllegalArgumentException("Product not found: " + id);
            em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        }
    }
    public SortedMap<Long, ProductSizeVariantEntity> lockSizes(Collection<Long> ids) {
        SortedMap<Long, ProductSizeVariantEntity> result = new TreeMap<>();
        for (Long id : new TreeSet<>(ids)) {
            ProductSizeVariantEntity size = em.find(ProductSizeVariantEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (size == null) throw new IllegalArgumentException("Size not found: " + id);
            em.refresh(size, LockModeType.PESSIMISTIC_WRITE);
            result.put(id, size);
        }
        return result;
    }
    public void change(ProductSizeVariantEntity size, String key, String kind, int delta) {
        // Hibernate may report PESSIMISTIC_FORCE_INCREMENT for newly persisted rows.
        if (!em.contains(size) || (em.getLockMode(size) != LockModeType.PESSIMISTIC_WRITE && em.getLockMode(size) != LockModeType.PESSIMISTIC_FORCE_INCREMENT))
            throw new IllegalStateException("Stock mutation requires a size write lock: managed=" + em.contains(size) + ", mode=" + (em.contains(size) ? em.getLockMode(size) : "detached"));
        var previous = sql.queryForList("select delta,kind from stock_movements where size_variant_id=? and operation_key=?", size.getId(), key);
        if (!previous.isEmpty()) {
            if (((Number)previous.get(0).get("delta")).intValue() != delta || !kind.equals(previous.get(0).get("kind")))
                throw new IllegalStateException("Operation key reused with different stock command");
            return;
        }
        Integer before = size.getInventory();
        if (before == null) throw new IllegalArgumentException("Inventory UNKNOWN");
        int after = Math.addExact(before, delta);
        if (after < 0) throw new IllegalArgumentException("Insufficient inventory");
        long revision = Math.addExact(size.getInventoryRevision(), 1);
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String actor = auth == null ? "system" : auth.getName();
        sql.update("insert into stock_movements(size_variant_id,operation_key,kind,before_inventory,delta,after_inventory,revision,actor) values(?,?,?,?,?,?,?,?)",
                size.getId(), key, kind, before, delta, after, revision, actor);
        size.setInventory(after); size.setInventoryRevision(revision);
    }
    public void initialize(ProductEntity product, Map<ProductSizeVariantEntity,Integer> initial) {
        lockProducts(List.of(product.getId()));
        var sizes = lockSizes(initial.keySet().stream().map(ProductSizeVariantEntity::getId).toList());
        initial.forEach((v,q) -> change(sizes.get(v.getId()), "initialize:" + v.getId(), "INITIALIZE", q));
    }
    public void adminSet(ProductSizeVariantEntity size, int target, Long expected) {
        if (target < 0 || size.getInventory() == null) throw new IllegalArgumentException("Invalid or UNKNOWN inventory");
        if (target == size.getInventory()) return; // metadata-only legacy clients never rewrite stock
        if (expected == null || expected != size.getInventoryRevision())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Inventory changed; reload product");
        change(size, "admin:" + size.getId() + ":" + expected, "ADMIN", Math.subtractExact(target, size.getInventory()));
    }
}
