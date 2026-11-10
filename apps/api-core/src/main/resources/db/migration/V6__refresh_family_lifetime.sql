-- Absolute lifetime of a refresh-token family (one login session). Rotation copies the value to every
-- successor and never issues a token that outlives it. Table privileges are unchanged: only api-core uses
-- this table.

ALTER TABLE refresh_token ADD COLUMN family_expires_at timestamptz;

-- Existing sessions get 30 days from the first token of their family, but never less than the token they hold
-- now, so no active session is cut short by this migration.
UPDATE refresh_token t
   SET family_expires_at = GREATEST(f.started_at + interval '30 days', t.expires_at)
  FROM (SELECT family_id, min(issued_at) AS started_at
          FROM refresh_token
         GROUP BY family_id) f
 WHERE f.family_id = t.family_id;

ALTER TABLE refresh_token ALTER COLUMN family_expires_at SET NOT NULL;
ALTER TABLE refresh_token ADD CONSTRAINT refresh_token_family_expiry_ck CHECK (family_expires_at >= expires_at);

COMMENT ON COLUMN refresh_token.family_expires_at IS
    'End of the session: shared by every token of the family; a refresh at or after it revokes the family.';
