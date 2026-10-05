-- Slug rule: 3–40 characters total (the V1 regex counted groups, letting hyphenated slugs reach 79 chars).
ALTER TABLE tenants DROP CONSTRAINT tenants_slug_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_slug_check
    CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(slug) BETWEEN 3 AND 40);

-- Each refresh token snapshots the user's token_version at issue; refresh rejects (and revokes the family of)
-- a token whose version no longer matches, closing the refresh-vs-logout-all race under READ COMMITTED.
ALTER TABLE refresh_tokens ADD COLUMN token_version integer NOT NULL DEFAULT 0;
