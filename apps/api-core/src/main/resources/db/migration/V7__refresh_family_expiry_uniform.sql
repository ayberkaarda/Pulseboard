-- V6 derived family_expires_at per row (GREATEST(first issue + 30 days, the row's own expiry)), so a family whose
-- later tokens outlived the 30 day mark could carry several different values. A family is one session and has
-- exactly one end: every row gets the maximum already computed in its family. Values only grow, so no active
-- session is shortened and refresh_token_family_expiry_ck (family_expires_at >= expires_at) stays valid.
--
-- Cost: one UPDATE that touches only rows of families that are inconsistent (normally a small share, usually none)
-- and rows that actually change. It takes ROW EXCLUSIVE on the table (reads and ordinary writes continue) and row
-- locks on the touched rows only; there is no table rewrite and no column or constraint change. WAL volume is
-- proportional to the touched rows (each updated row is written once, plus index entries only if an indexed column
-- changes, which family_expires_at is not). The GROUP BY reads the table once.

UPDATE refresh_token t
   SET family_expires_at = f.family_end
  FROM (SELECT family_id, max(family_expires_at) AS family_end
          FROM refresh_token
         GROUP BY family_id
        HAVING min(family_expires_at) <> max(family_expires_at)) f
 WHERE t.family_id = f.family_id
   AND t.family_expires_at <> f.family_end;
