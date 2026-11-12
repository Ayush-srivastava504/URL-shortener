CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE short_urls (
    id           BIGSERIAL PRIMARY KEY,
    short_code   VARCHAR(32)   NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    owner_id     BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    click_count  BIGINT        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ
);

-- Every redirect looks up by short_code, so it gets a unique B-tree index.
CREATE UNIQUE INDEX ux_short_urls_short_code ON short_urls (short_code);

-- The "my links" listing filters by owner, newest first.
CREATE INDEX ix_short_urls_owner_created ON short_urls (owner_id, created_at DESC);
