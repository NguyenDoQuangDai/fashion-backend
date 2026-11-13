CREATE TABLE user_product_views (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id text NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    last_viewed_at timestamptz NOT NULL,
    CONSTRAINT uq_user_product_views UNIQUE (user_id, product_id)
);
CREATE INDEX idx_user_product_views_recent ON user_product_views(user_id, last_viewed_at DESC);
