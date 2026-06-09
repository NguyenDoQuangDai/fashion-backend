CREATE TABLE product_reviews (
    id bigserial PRIMARY KEY,
    product_id text NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    guest_name text,
    guest_email text,
    rating int NOT NULL,
    comment text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_reviews_product ON product_reviews(product_id);
