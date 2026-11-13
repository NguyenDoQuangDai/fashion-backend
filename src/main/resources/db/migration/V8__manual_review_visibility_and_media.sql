ALTER TABLE product_reviews
    ADD COLUMN IF NOT EXISTS visibility varchar(16);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'product_reviews' AND column_name = 'status'
    ) THEN
        UPDATE product_reviews
        SET visibility = CASE WHEN status = 'APPROVED' THEN 'VISIBLE' ELSE 'HIDDEN' END
        WHERE visibility IS NULL;
    ELSE
        UPDATE product_reviews SET visibility = 'HIDDEN' WHERE visibility IS NULL;
    END IF;
END $$;

UPDATE product_reviews
SET visibility = 'HIDDEN'
WHERE visibility IS NULL;

ALTER TABLE product_reviews
    ALTER COLUMN visibility SET DEFAULT 'HIDDEN';

ALTER TABLE product_review_images
    ADD COLUMN IF NOT EXISTS media_type varchar(16) NOT NULL DEFAULT 'IMAGE';

CREATE INDEX IF NOT EXISTS idx_product_reviews_visibility ON product_reviews(visibility);
CREATE INDEX IF NOT EXISTS idx_product_review_images_media_type ON product_review_images(media_type);
