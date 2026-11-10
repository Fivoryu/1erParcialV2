-- Platform administrators can see and open every project (read access only). The flag is set by an
-- operator directly in the database; no API can grant it, so nobody can promote themselves.
ALTER TABLE diagrammer.user_account ADD COLUMN is_platform_admin BOOLEAN NOT NULL DEFAULT false;
