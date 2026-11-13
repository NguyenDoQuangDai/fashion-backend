-- PostgreSQL CHECK accepts UNKNOWN, so require non-null route and adjustment metadata explicitly.
ALTER TABLE orders ADD CONSTRAINT orders_shipping_adjustment_metadata_check CHECK (
    (shipping_fee_adjusted_at IS NULL AND shipping_fee_original IS NULL)
    OR
    (shipping_fee_adjusted_at IS NOT NULL AND shipping_fee_original IS NOT NULL
        AND items_subtotal IS NOT NULL AND total IS NOT NULL)
);

ALTER TABLE orders ADD CONSTRAINT orders_shipping_route_metadata_check CHECK (
    shipping_distance_meters IS NULL
    OR
    (shipping_provider IS NOT NULL AND shipping_vehicle IS NOT NULL
        AND shipping_calculated_at IS NOT NULL AND shipping_route_status IS NOT NULL
        AND delivery_address_source IS NOT NULL AND items_subtotal IS NOT NULL AND total IS NOT NULL)
);
