-- ---------------------------------------------------------------------------
-- IA4-07: per-view relationship style (DEC-I4-07). One row per (view, target);
-- the appearance travels separately from the model semantics, so the XMI
-- emitter/reader are untouched. TargetType is frozen to RELATIONSHIP in this
-- unit; the check constraint is widened later if a new target type is declared.
-- ---------------------------------------------------------------------------
CREATE TABLE view_style (
    style_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id       UUID NOT NULL REFERENCES diagram_view(view_id) ON DELETE CASCADE,
    target_type   VARCHAR(32) NOT NULL CHECK (target_type IN ('RELATIONSHIP')),
    target_id     UUID NOT NULL,
    routing       VARCHAR(16) NOT NULL DEFAULT 'STRAIGHT' CHECK (routing IN ('STRAIGHT', 'FREE', 'ORTHOGONAL')),
    corner_radius INT NOT NULL DEFAULT 0 CHECK (corner_radius >= 0),
    stroke_width  INT NOT NULL DEFAULT 2 CHECK (stroke_width >= 1),
    dashed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_view_style_view_target UNIQUE (view_id, target_id)
);

CREATE INDEX idx_view_style_view ON view_style(view_id);

COMMENT ON TABLE view_style IS 'IA4-07: apariencia por vista para relaciones (routing, esquinas, trazo). Comando versionado style.set; fuera del XMI.';
