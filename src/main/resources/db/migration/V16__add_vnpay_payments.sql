-- Additive migration; backfill the merchant's existing COD test orders only.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS payment_method varchar(16),
    ADD COLUMN IF NOT EXISTS payment_status varchar(16),
    ADD COLUMN IF NOT EXISTS paid_at timestamptz;

UPDATE orders SET payment_method = 'COD' WHERE payment_method IS NULL;
UPDATE orders SET payment_status = CASE lower(status)
    WHEN 'completed' THEN 'PAID' WHEN 'cancelled' THEN 'FAILED' ELSE 'PENDING' END
WHERE payment_method = 'COD' AND payment_status IS NULL;

ALTER TABLE orders
    ALTER COLUMN payment_method SET DEFAULT 'COD',
    ALTER COLUMN payment_method SET NOT NULL,
    ALTER COLUMN payment_status SET DEFAULT 'PENDING',
    ALTER COLUMN payment_status SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_payment_method_check CHECK (payment_method IN ('COD', 'VNPAY'));
ALTER TABLE orders ADD CONSTRAINT orders_payment_status_check CHECK (payment_status IN ('PENDING', 'PAID', 'FAILED'));
ALTER TABLE orders ADD CONSTRAINT orders_paid_at_check CHECK (paid_at IS NULL OR payment_status = 'PAID');

CREATE TABLE vnpay_payments (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    txn_ref varchar(32) NOT NULL UNIQUE,
    amount numeric(12,2) NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric),
    access_token_hash varchar(64) NOT NULL,
    payment_url text,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    review_after timestamptz NOT NULL,
    confirmed_at timestamptz,
    confirmed_source varchar(16) CHECK (confirmed_source IN ('IPN', 'ADMIN')),
    response_code varchar(2),
    transaction_status varchar(2),
    transaction_no varchar(32),
    bank_code varchar(32),
    gateway_pay_date varchar(14),
    failure_reason varchar(64),
    requires_review boolean NOT NULL DEFAULT false,
    CHECK (expires_at > created_at AND review_after >= expires_at),
    CHECK ((confirmed_at IS NULL) = (confirmed_source IS NULL))
);

CREATE TABLE payment_history (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES vnpay_payments(id),
    source varchar(16) NOT NULL CHECK (source IN ('IPN', 'ADMIN')),
    event_key varchar(64) NOT NULL,
    previous_status varchar(16) NOT NULL CHECK (previous_status IN ('PENDING','PAID','FAILED')),
    new_status varchar(16) NOT NULL CHECK (new_status IN ('PENDING','PAID','FAILED')),
    actor_id uuid REFERENCES users(id) ON DELETE SET NULL,
    actor_name text,
    created_at timestamptz NOT NULL,
    note text NOT NULL CHECK (length(trim(note)) > 0),
    evidence text,
    UNIQUE (payment_id, source, event_key),
    CHECK (source <> 'ADMIN' OR actor_name IS NOT NULL)
);
CREATE INDEX payment_history_payment_created_idx ON payment_history(payment_id, created_at);
CREATE INDEX vnpay_payments_review_idx ON vnpay_payments(review_after) WHERE confirmed_at IS NULL;
