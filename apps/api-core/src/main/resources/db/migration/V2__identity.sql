-- Identity: users, API keys and rotating refresh tokens. Secrets are stored only as hashes.

CREATE TABLE app_user (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    email         varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role          varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT app_user_tenant_email_uk UNIQUE (tenant_id, email),
    -- Target of composite foreign keys that pin dependent rows to the same tenant.
    CONSTRAINT app_user_id_tenant_uk UNIQUE (id, tenant_id),
    CONSTRAINT app_user_email_lowercase_ck CHECK (email = lower(email)),
    CONSTRAINT app_user_role_ck CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER'))
);

COMMENT ON TABLE app_user IS 'Human user of a tenant; password_hash is an Argon2id encoded hash.';

CREATE TABLE api_key (
    id           uuid         PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    name         varchar(100) NOT NULL,
    key_hash     varchar(64)  NOT NULL,
    prefix       varchar(8)   NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz,
    CONSTRAINT api_key_key_hash_uk UNIQUE (key_hash),
    CONSTRAINT api_key_key_hash_format_ck CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT api_key_prefix_length_ck CHECK (char_length(prefix) = 8),
    CONSTRAINT api_key_name_not_blank_ck CHECK (btrim(name) <> '')
);

CREATE INDEX api_key_tenant_idx ON api_key (tenant_id);

COMMENT ON TABLE api_key IS 'Machine credential; key_hash is the hex SHA-256 of the full key, prefix is shown to users.';

CREATE TABLE refresh_token (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    family_id   uuid        NOT NULL,
    token_hash  varchar(64) NOT NULL,
    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid REFERENCES refresh_token (id) ON DELETE SET NULL,
    CONSTRAINT refresh_token_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT refresh_token_token_hash_format_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_token_expiry_ck CHECK (expires_at > issued_at),
    CONSTRAINT refresh_token_user_fk FOREIGN KEY (user_id, tenant_id)
        REFERENCES app_user (id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);

COMMENT ON TABLE refresh_token IS
    'Rotating refresh tokens. A family is one login session; reuse of a rotated token revokes the whole family.';
