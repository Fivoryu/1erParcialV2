ALTER TABLE diagrammer.uml_model
    ADD COLUMN active_branch_id UUID REFERENCES diagrammer.vcs_branch(branch_id) ON DELETE SET NULL;
