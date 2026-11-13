ALTER TABLE product_reviews
    ADD COLUMN IF NOT EXISTS status varchar(32) NOT NULL DEFAULT 'PENDING_ADMIN',
    ADD COLUMN IF NOT EXISTS verified_purchase boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS order_id uuid,
    ADD COLUMN IF NOT EXISTS ai_text_verdict varchar(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS ai_text_confidence double precision,
    ADD COLUMN IF NOT EXISTS ai_text_threshold double precision,
    ADD COLUMN IF NOT EXISTS ai_text_reason text,
    ADD COLUMN IF NOT EXISTS ai_text_error_status varchar(64),
    ADD COLUMN IF NOT EXISTS ai_text_error_reason text,
    ADD COLUMN IF NOT EXISTS ai_text_moderated_at timestamptz,
    ADD COLUMN IF NOT EXISTS moderated_by uuid,
    ADD COLUMN IF NOT EXISTS moderated_at timestamptz,
    ADD COLUMN IF NOT EXISTS moderation_reason text,
    ADD COLUMN IF NOT EXISTS updated_at timestamptz;

UPDATE product_reviews SET status = 'PENDING_ADMIN' WHERE status IS NULL;

CREATE TABLE IF NOT EXISTS product_review_images (
    id bigserial PRIMARY KEY,
    review_id bigint NOT NULL REFERENCES product_reviews(id) ON DELETE CASCADE,
    storage_key text NOT NULL,
    image_url text,
    mime_type varchar(128) NOT NULL,
    byte_size bigint NOT NULL,
    checksum varchar(128),
    display_order int NOT NULL,
    ai_verdict varchar(32) NOT NULL DEFAULT 'PENDING',
    ai_confidence double precision,
    ai_threshold double precision,
    ai_reason text,
    ai_error_status varchar(64),
    ai_error_reason text,
    ai_moderated_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_product_reviews_status ON product_reviews(status);
CREATE INDEX IF NOT EXISTS idx_product_review_images_review ON product_review_images(review_id);
CREATE INDEX IF NOT EXISTS idx_product_review_images_ai_verdict ON product_review_images(ai_verdict);