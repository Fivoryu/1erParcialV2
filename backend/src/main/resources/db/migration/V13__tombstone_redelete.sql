-- V13 (IA4 acceptance-harness fix): tombstone re-deletion.
-- V3 declared uq_tombstone_target UNIQUE (target_type, target_id), which made a tombstone unique
-- per target FOREVER: undoing a deletion resurrects the element, and re-deleting that recreated
-- element crashed with MODEL_INVALID ("duplicate key value violates unique constraint
-- uq_tombstone_target"). Uniqueness is now scoped to the cascade group: one tombstone per target
-- PER cascade, so recreating and re-deleting is legal while a single cascade still cannot
-- double-tombstone the same target.
ALTER TABLE diagrammer.tombstone
    DROP CONSTRAINT uq_tombstone_target;

ALTER TABLE diagrammer.tombstone
    ADD CONSTRAINT uq_tombstone_cascade_target UNIQUE (cascade_id, target_type, target_id);
