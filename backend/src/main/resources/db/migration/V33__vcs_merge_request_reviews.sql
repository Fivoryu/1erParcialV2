-- Request-changes reviews are history, not an additional merge gate.
CREATE TABLE diagrammer.vcs_merge_request_review (
    review_id UUID PRIMARY KEY,
    merge_request_id UUID NOT NULL REFERENCES diagrammer.vcs_merge_request(merge_request_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id),
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX vcs_merge_request_review_history_idx
    ON diagrammer.vcs_merge_request_review (merge_request_id, created_at, review_id);
