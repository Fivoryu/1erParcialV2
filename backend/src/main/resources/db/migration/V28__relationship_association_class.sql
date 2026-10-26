-- UML association class: a class that is also the association between two others. The relationship points at the
-- class; NULL means the association has none, so existing models and their state hashes are unchanged. Deleting the
-- class releases the link and keeps the relationship, and a class is the association class of one relationship only.
ALTER TABLE diagrammer.uml_relationship
    ADD COLUMN association_class_id UUID REFERENCES diagrammer.classifier (classifier_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_relationship_association_class
    ON diagrammer.uml_relationship (association_class_id)
    WHERE association_class_id IS NOT NULL;

COMMENT ON COLUMN diagrammer.uml_relationship.association_class_id IS
    'Class that is also this association (UML association class); NULL when the association has none.';
