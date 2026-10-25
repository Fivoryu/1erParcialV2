-- Hybrid persistence: a class can be generated as a relational entity or as a document. NULL means the
-- automatic classifier decides, so existing models and their state hashes are unchanged.
ALTER TABLE diagrammer.classifier
    ADD COLUMN store_override VARCHAR(16);

ALTER TABLE diagrammer.classifier
    ADD CONSTRAINT ck_classifier_store_override CHECK (store_override IS NULL OR store_override IN ('SQL', 'DOCUMENT'));

COMMENT ON COLUMN diagrammer.classifier.store_override IS
    'Manual persistence store of the class (SQL or DOCUMENT); NULL leaves the choice to the automatic classifier.';
