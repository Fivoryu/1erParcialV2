-- Selected notification recipients do not change approval or merge rules.
CREATE TABLE diagrammer.vcs_merge_request_reviewer (
    merge_request_id UUID NOT NULL REFERENCES diagrammer.vcs_merge_request(merge_request_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    PRIMARY KEY (merge_request_id, user_id)
);
