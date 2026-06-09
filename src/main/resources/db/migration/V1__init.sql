CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE roles (
    id bigserial PRIMARY KEY,
    name text UNIQUE NOT NULL
);

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email text UNIQUE NOT NULL,
    password_hash text NOT NULL,
    full_name text,
    phone text,
    gender text,
    preferred_categories jsonb,
    price_min numeric(12, 2),
    price_max numeric(12, 2),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id bigint NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token text UNIQUE NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE password_reset_tokens (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token text UNIQUE NOT NULL,
    expires_at timestamptz NOT NULL,
    used boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id text PRIMARY KEY,
    slug text UNIQUE,
    title text NOT NULL,
    brand text,
    gender text,
    category_name text,
    category_slug text,
    type text,
    short_description text,
    description text,
    canonical_url text,
    price numeric(12, 2),
    price_promotion numeric(12, 2),
    tags jsonb,
    cover_image text,
    general_images jsonb,
    sold_count int,
    url text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE product_color_variants (
    id bigserial PRIMARY KEY,
    product_id text NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    color_id text,
    color_name text,
    color_code text,
    cover_image text,
    sort_order int
);

CREATE TABLE product_color_images (
    id bigserial PRIMARY KEY,
    color_variant_id bigint NOT NULL REFERENCES product_color_variants(id) ON DELETE CASCADE,
    image_url text NOT NULL,
    sort_order int
);

CREATE TABLE product_size_variants (
    id bigserial PRIMARY KEY,
    color_variant_id bigint NOT NULL REFERENCES product_color_variants(id) ON DELETE CASCADE,
    size_id text,
    size_name text,
    sku text,
    inventory int,
    price numeric(12, 2),
    price_promotion numeric(12, 2)
);

CREATE TABLE carts (
    id uuid PRIMARY KEY,
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    guest_id text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cart_items (
    id bigserial PRIMARY KEY,
    cart_id uuid NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id text NOT NULL REFERENCES products(id),
    color_variant_id bigint REFERENCES product_color_variants(id),
    size_variant_id bigint REFERENCES product_size_variants(id),
    quantity int NOT NULL,
    price_snapshot numeric(12, 2)
);

CREATE TABLE orders (
    id uuid PRIMARY KEY,
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    guest_id text,
    status text NOT NULL,
    total numeric(12, 2),
    email text,
    full_name text,
    phone text,
    address_line text,
    city text,
    note text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id bigserial PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id text NOT NULL REFERENCES products(id),
    title_snapshot text,
    color_snapshot text,
    size_snapshot text,
    quantity int NOT NULL,
    price_snapshot numeric(12, 2)
);

CREATE TABLE addresses (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label text,
    full_name text,
    phone text,
    line1 text,
    line2 text,
    city text,
    province text,
    postal_code text,
    is_default boolean NOT NULL DEFAULT false
);

CREATE TABLE notifications (
    id bigserial PRIMARY KEY,
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    email text,
    type text NOT NULL,
    payload jsonb,
    status text NOT NULL DEFAULT 'pending',
    sent_at timestamptz
);

CREATE INDEX idx_products_price ON products(price);
CREATE INDEX idx_products_gender ON products(gender);
CREATE INDEX idx_products_category ON products(category_slug);
CREATE INDEX idx_products_title_trgm ON products USING gin (title gin_trgm_ops);
CREATE INDEX idx_products_desc_trgm ON products USING gin (description gin_trgm_ops);
