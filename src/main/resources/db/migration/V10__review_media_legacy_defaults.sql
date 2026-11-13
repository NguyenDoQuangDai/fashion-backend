ALTER TABLE product_review_images
    ALTER COLUMN ai_verdict SET DEFAULT 'PENDING';

ALTER TABLE product_review_images
    ALTER COLUMN media_type SET DEFAULT 'IMAGE';

UPDATE product_review_images
SET ai_verdict = 'PENDING'
WHERE ai_verdict IS NULL;

UPDATE product_review_images
SET media_type = 'IMAGE'
WHERE media_type IS NULL;
