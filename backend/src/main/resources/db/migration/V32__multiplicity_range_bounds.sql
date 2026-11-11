-- ---------------------------------------------------------------------------
-- Numeric bounds for RANGE multiplicity (UML lowerValue/upperValue).
-- Additive: nullable columns. RANGE is the discriminator; lower_bound is
-- required for RANGE and upper_bound NULL means unbounded (*).
-- ---------------------------------------------------------------------------
ALTER TABLE diagrammer.uml_attribute
    ADD COLUMN lower_bound INTEGER,
    ADD COLUMN upper_bound INTEGER;

ALTER TABLE diagrammer.relationship_end
    ADD COLUMN lower_bound INTEGER,
    ADD COLUMN upper_bound INTEGER;

-- Legacy RANGE rows carried no bounds: backfill them to 0..*.
UPDATE diagrammer.uml_attribute SET lower_bound = 0 WHERE multiplicity = 'RANGE';
UPDATE diagrammer.relationship_end SET lower_bound = 0 WHERE multiplicity = 'RANGE';

ALTER TABLE diagrammer.uml_attribute
    ADD CONSTRAINT chk_attribute_range_bounds CHECK (
        (multiplicity = 'RANGE' AND lower_bound IS NOT NULL AND lower_bound >= 0
            AND (upper_bound IS NULL OR upper_bound >= GREATEST(lower_bound, 1)))
        OR (multiplicity <> 'RANGE' AND lower_bound IS NULL AND upper_bound IS NULL));

ALTER TABLE diagrammer.relationship_end
    ADD CONSTRAINT chk_relationship_end_range_bounds CHECK (
        (multiplicity = 'RANGE' AND lower_bound IS NOT NULL AND lower_bound >= 0
            AND (upper_bound IS NULL OR upper_bound >= GREATEST(lower_bound, 1)))
        OR (multiplicity <> 'RANGE' AND lower_bound IS NULL AND upper_bound IS NULL));
