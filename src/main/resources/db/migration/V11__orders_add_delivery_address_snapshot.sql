-- Existing orders deliberately keep NULL snapshots; never infer a delivery location.
ALTER TABLE orders
    ADD COLUMN delivery_formatted_address text,
    ADD COLUMN delivery_latitude numeric(10, 7),
    ADD COLUMN delivery_longitude numeric(10, 7),
    ADD COLUMN delivery_place_id text,
    ADD COLUMN delivery_address_source varchar(32);

ALTER TABLE orders ADD CONSTRAINT orders_delivery_address_snapshot_check CHECK (
    (delivery_formatted_address IS NULL AND delivery_latitude IS NULL
        AND delivery_longitude IS NULL AND delivery_place_id IS NULL AND delivery_address_source IS NULL)
    OR
    (delivery_formatted_address IS NOT NULL AND length(trim(delivery_formatted_address)) > 0
        AND delivery_latitude IS NOT NULL AND delivery_latitude BETWEEN -90 AND 90
        AND delivery_longitude IS NOT NULL AND delivery_longitude BETWEEN -180 AND 180
        AND delivery_place_id IS NOT NULL AND length(trim(delivery_place_id)) > 0
        AND delivery_address_source IS NOT NULL AND delivery_address_source = 'GOGODUK')
);
