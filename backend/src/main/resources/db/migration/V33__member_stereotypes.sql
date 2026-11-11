-- UML stereotypes on classifiers, attributes and operations (T3). One stereotype per element, chosen from a
-- fixed list. NULL means none, so existing models and their state hashes are unchanged. The command handlers
-- enforce the allowed names per owner kind (Java enums), so the columns stay plain strings a later UML version
-- can extend, exactly like the relationship stereotype of V27.
ALTER TABLE diagrammer.classifier
    ADD COLUMN stereotype VARCHAR(32);
ALTER TABLE diagrammer.uml_attribute
    ADD COLUMN stereotype VARCHAR(32);
ALTER TABLE diagrammer.uml_operation
    ADD COLUMN stereotype VARCHAR(32);

COMMENT ON COLUMN diagrammer.classifier.stereotype IS
    'ClassifierStereotype name (ENTITY, BOUNDARY, ...); only CLASS and ABSTRACT_CLASS carry one; NULL when none.';
COMMENT ON COLUMN diagrammer.uml_attribute.stereotype IS
    'AttributeStereotype name (ID, UNIQUE, READONLY); NULL when none.';
COMMENT ON COLUMN diagrammer.uml_operation.stereotype IS
    'OperationStereotype name (CREATE, DESTROY, QUERY); NULL when none.';
