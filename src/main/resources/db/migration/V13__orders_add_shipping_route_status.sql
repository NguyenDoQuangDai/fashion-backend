-- Keep the provider's routing status with each new shipping quote.
-- Older quoted orders used only real road routes; historical orders stay untouched.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_route_status varchar(16);
UPDATE orders SET shipping_route_status = 'OK'
WHERE shipping_provider = 'GOGODUK' AND shipping_distance_meters IS NOT NULL
  AND shipping_route_status IS NULL;
ALTER TABLE orders ADD CONSTRAINT orders_shipping_route_status_check CHECK (
    (shipping_distance_meters IS NULL AND shipping_route_status IS NULL)
    OR
    (shipping_distance_meters IS NOT NULL AND shipping_route_status IN ('OK', 'ESTIMATED'))
);
