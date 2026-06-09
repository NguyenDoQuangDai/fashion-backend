ALTER TABLE orders ADD COLUMN IF NOT EXISTS updated_at timestamptz;
UPDATE orders SET updated_at = created_at WHERE updated_at IS NULL;
