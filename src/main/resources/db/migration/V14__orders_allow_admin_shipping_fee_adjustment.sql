-- Preserve the quoted fee while allowing an explicit admin adjustment to the payable fee.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shipping_fee_original numeric(12, 2),
    ADD COLUMN IF NOT EXISTS shipping_fee_adjusted_at timestamptz;

ALTER TABLE orders DROP CONSTRAINT orders_shipping_snapshot_check;
ALTER TABLE orders ADD CONSTRAINT orders_shipping_snapshot_check CHECK (
    (shipping_distance_meters IS NULL AND shipping_provider IS NULL AND shipping_vehicle IS NULL
        AND shipping_calculated_at IS NULL AND shipping_route_status IS NULL
        AND (
            (items_subtotal IS NULL AND shipping_fee = 0
                AND shipping_fee_original IS NULL AND shipping_fee_adjusted_at IS NULL)
            OR
            (items_subtotal IS NOT NULL AND items_subtotal >= 0
                AND shipping_fee >= 0 AND shipping_fee_original = 0
                AND shipping_fee_adjusted_at IS NOT NULL AND total = items_subtotal + shipping_fee)
        ))
    OR
    (shipping_distance_meters IS NOT NULL AND shipping_distance_meters >= 0
        AND shipping_distance_meters < 'Infinity'::numeric
        AND items_subtotal IS NOT NULL AND items_subtotal >= 0 AND items_subtotal < 'Infinity'::numeric
        AND shipping_provider = 'GOGODUK' AND shipping_vehicle = 'motobike'
        AND shipping_calculated_at IS NOT NULL AND shipping_route_status IN ('OK', 'ESTIMATED')
        AND delivery_address_source = 'GOGODUK' AND total = items_subtotal + shipping_fee
        AND shipping_fee >= 0
        AND (
            (shipping_fee_original IS NULL AND shipping_fee_adjusted_at IS NULL
                AND shipping_fee = least(50000, ceil(greatest(0, shipping_distance_meters - 5000) / 1000) * 3000))
            OR
            (shipping_fee_original = least(50000, ceil(greatest(0, shipping_distance_meters - 5000) / 1000) * 3000)
                AND shipping_fee_adjusted_at IS NOT NULL)
        ))
);
