-- Expand provider support without changing any historical order snapshots or prices.
ALTER TABLE orders DROP CONSTRAINT orders_delivery_address_snapshot_check;
ALTER TABLE orders ADD CONSTRAINT orders_delivery_address_snapshot_check CHECK (
    (delivery_formatted_address IS NULL AND delivery_latitude IS NULL
        AND delivery_longitude IS NULL AND delivery_place_id IS NULL AND delivery_address_source IS NULL)
    OR
    (delivery_formatted_address IS NOT NULL AND length(trim(delivery_formatted_address)) > 0
        AND delivery_latitude IS NOT NULL AND delivery_latitude BETWEEN -90 AND 90
        AND delivery_longitude IS NOT NULL AND delivery_longitude BETWEEN -180 AND 180
        AND delivery_place_id IS NOT NULL AND length(trim(delivery_place_id)) > 0
        AND delivery_address_source IS NOT NULL AND delivery_address_source IN ('GOGODUK', 'GEOAPIFY'))
);

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
        AND ((shipping_provider = 'GOGODUK' AND shipping_vehicle = 'motobike')
             OR (shipping_provider = 'GEOAPIFY' AND shipping_vehicle IN ('scooter','motorcycle')))
        AND shipping_calculated_at IS NOT NULL AND shipping_route_status IN ('OK', 'ESTIMATED')
        AND delivery_address_source IN ('GOGODUK','GEOAPIFY') AND total = items_subtotal + shipping_fee
        AND shipping_fee >= 0
        AND (
            (shipping_fee_original IS NULL AND shipping_fee_adjusted_at IS NULL
                AND shipping_fee = least(50000, ceil(greatest(0, shipping_distance_meters - 5000) / 1000) * 3000))
            OR
            (shipping_fee_original = least(50000, ceil(greatest(0, shipping_distance_meters - 5000) / 1000) * 3000)
                AND shipping_fee_adjusted_at IS NOT NULL)
        ))
);

-- Shared across backend instances using the same database/schema.
CREATE TABLE checkout_provider_cache (
    cache_key varchar(160) PRIMARY KEY,
    payload text NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX checkout_provider_cache_expiry ON checkout_provider_cache(expires_at);
CREATE TABLE checkout_provider_limits (
    bucket varchar(160) PRIMARY KEY,
    window_id bigint NOT NULL,
    request_count integer NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX checkout_provider_limits_expiry ON checkout_provider_limits(expires_at);
