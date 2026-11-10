-- Tenancy root aggregate. Every tenant-owned row in later migrations references tenant(id).

CREATE TABLE tenant (
    id         uuid         PRIMARY KEY,
    slug       varchar(63)  NOT NULL,
    name       varchar(200) NOT NULL,
    plan       varchar(16)  NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT tenant_slug_uk UNIQUE (slug),
    CONSTRAINT tenant_slug_format_ck CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$'),
    CONSTRAINT tenant_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT tenant_plan_ck CHECK (plan IN ('FREE', 'PRO'))
);

COMMENT ON TABLE tenant IS 'Customer organisation; the isolation boundary for all tenant-scoped data.';
