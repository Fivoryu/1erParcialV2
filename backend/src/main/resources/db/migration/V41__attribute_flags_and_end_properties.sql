-- ---------------------------------------------------------------------------
-- UML attribute isStatic / isDerived and relationship-end isOrdered / isUnique.
-- Additive: boolean columns that default to false, so legacy rows read as
-- "not marked" and existing snapshots keep their hashes.
-- ---------------------------------------------------------------------------
ALTER TABLE diagrammer.uml_attribute
    ADD COLUMN is_static  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_derived BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE diagrammer.relationship_end
    ADD COLUMN is_ordered BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_unique  BOOLEAN NOT NULL DEFAULT FALSE;
