-- UML dependency stereotypes («use», «import», «include», ...). NULL means the dependency carries none, so
-- existing models and their state hashes are unchanged. Only a DEPENDENCY may carry one; the command handlers
-- enforce that and the allowed names, so the column stays a plain string a later UML version can extend.
ALTER TABLE diagrammer.uml_relationship
    ADD COLUMN stereotype VARCHAR(32);

COMMENT ON COLUMN diagrammer.uml_relationship.stereotype IS
    'Stereotype of a DEPENDENCY relationship (USE, IMPORT, ...); NULL when it has none.';
