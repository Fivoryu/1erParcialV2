-- UML notes (T4): a note is a model-level comment annotating zero or more classifiers or relationships
-- (UML Comment.annotatedElement 0..*). Additive: no existing table changes except one new target_type label, so
-- existing models and their state hashes are unchanged. The note placed on the canvas is an ordinary
-- element_geometry row whose target_type is NOTE.
ALTER TYPE diagrammer.target_type ADD VALUE IF NOT EXISTS 'NOTE';

CREATE TABLE diagrammer.uml_note (
    note_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id   UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    body       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_note_body CHECK (length(btrim(body)) > 0 AND char_length(body) <= 2000)
);
CREATE INDEX idx_note_model ON diagrammer.uml_note (model_id);

-- Annotated elements of a note. target_id is polymorphic (no foreign key, like element_geometry); the command
-- handlers remove a link when its classifier or relationship is deleted.
CREATE TABLE diagrammer.uml_note_link (
    note_id     UUID NOT NULL REFERENCES diagrammer.uml_note(note_id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL,
    target_id   UUID NOT NULL,
    CONSTRAINT pk_note_link PRIMARY KEY (note_id, target_type, target_id),
    CONSTRAINT chk_note_link_target_type CHECK (target_type IN ('CLASSIFIER', 'RELATIONSHIP'))
);
CREATE INDEX idx_note_link_target ON diagrammer.uml_note_link (target_type, target_id);

COMMENT ON TABLE diagrammer.uml_note IS 'UML Comment: free text of a note, 1..2000 characters.';
COMMENT ON TABLE diagrammer.uml_note_link IS 'Annotated elements of a note: CLASSIFIER or RELATIONSHIP ids of the same model.';
