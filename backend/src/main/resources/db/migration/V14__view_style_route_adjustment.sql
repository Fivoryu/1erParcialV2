-- ---------------------------------------------------------------------------
-- ROR-01/ROR-02 (rutas ortogonales ajustables): frame-relative orthogonal route
-- adjustment, persisted on the per-view style row next to the appearance fields.
-- Outside the XMI like the rest of view_style; it never participates in the
-- state hash nor in the undo log (style.set does not enter the undo).
-- ---------------------------------------------------------------------------
ALTER TABLE view_style ADD COLUMN route_adjustment JSONB;

ALTER TABLE view_style
    ADD CONSTRAINT ck_view_style_route_adjustment_object
    CHECK (route_adjustment IS NULL OR jsonb_typeof(route_adjustment) = 'object');

COMMENT ON COLUMN view_style.route_adjustment IS
    'ROR-02: ajuste ortogonal relativo al trazado automatico, fuera del XMI. JSON: {"axis":"H"|"V","corridors":[int,...],"levels":[int,...]} con levels.length === corridors.length - 1. NULL cuando la fila no tiene ajuste.';
