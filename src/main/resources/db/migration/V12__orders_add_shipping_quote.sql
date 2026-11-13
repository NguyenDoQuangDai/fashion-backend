-- IF NOT EXISTS also supports development databases touched by Hibernate ddl-auto=update.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS items_subtotal numeric(12, 2),
    ADD COLUMN IF NOT EXISTS shipping_fee numeric(12, 2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS shipping_distance_meters numeric,
    ADD COLUMN IF NOT EXISTS shipping_provider varchar(32),
    ADD COLUMN IF NOT EXISTS shipping_vehicle varchar(32),
    ADD COLUMN IF NOT EXISTS shipping_calculated_at timestamptz;

-- Only the new fee column is initialized. Historical total/address/note/status stay untouched.
UPDATE orders SET shipping_fee = 0 WHERE shipping_fee IS NULL;
ALTER TABLE orders ALTER COLUMN shipping_fee SET DEFAULT 0, ALTER COLUMN shipping_fee SET NOT NULL;

ALTER TABLE orders ADD CONSTRAINT orders_shipping_snapshot_check CHECK (
    (shipping_distance_meters IS NULL AND items_subtotal IS NULL AND shipping_provider IS NULL
        AND shipping_vehicle IS NULL AND shipping_calculated_at IS NULL AND shipping_fee = 0)
    OR
    (shipping_distance_meters IS NOT NULL AND shipping_distance_meters >= 0
        AND shipping_distance_meters < 'Infinity'::numeric
        AND items_subtotal IS NOT NULL AND items_subtotal >= 0 AND items_subtotal < 'Infinity'::numeric
        AND shipping_provider IS NOT NULL AND shipping_provider = 'GOGODUK'
        AND shipping_vehicle IS NOT NULL AND shipping_vehicle = 'motobike'
        AND shipping_calculated_at IS NOT NULL
        AND delivery_address_source IS NOT NULL AND delivery_address_source = 'GOGODUK'
        AND total IS NOT NULL AND total = items_subtotal + shipping_fee
        AND shipping_fee = least(50000, ceil(greatest(0, shipping_distance_meters - 5000) / 1000) * 3000))
);
