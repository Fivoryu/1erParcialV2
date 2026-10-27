# VCS diffs for commits and pull requests, with authors

Objective: GitHub-like browsing of history with field-level diffs for commits and merge requests (PR), showing who authored each commit.
Branch: feat/vcs-diffs-and-authors · Delivery: ask-on-risk · Test runner: `mvn -o test` (backend), `npm test` in `web/` (frontend).

## Specs

- S1: "Se puede ver por diferentes commits como en github?" — browse the history commit by commit (pick a commit, see its detail, compare two commits).
- S2: "falta añadir las diff para un PR y los commits" — a diff for each commit and for each merge request (PR).
- S3: "que en los commits se vea el autor/autores" — each commit shows its author(s), in the history list and in the commit detail, and the PR shows who contributed.

Assumptions (not requested verbatim, marked as assumptions):
- A1: the diff starts as a structured, field-level text diff (before → after, attributes and operations included). A visual diff on the canvas is stage 2 unless the user asks for it first.
- A2 (revised, see L4): "autores" = the commit's author; for a merge commit also everyone whose commits the merge brought in; for a PR everyone who authored its commits. Recording who changed the model between two commits (shared working copy) needs a migration and is phase 2, only if the user asks.
- A3: a PR diff = what the source branch adds since the merge base (what GitHub shows), next to the conflict list.

## Tasks

- T1 (S2) backend: field-level changes: `details` in the commit detail via `SnapshotDiff`; existing `changes` unchanged — route: inline — status: done — commit: 81272b5
- T2 (S3) backend: `authors` in the commit detail (AUTHOR, or MERGED_BY plus CONTRIBUTOR for merge commits) — route: inline — status: done — commit: c9d50b0
- T3 (S2, S3) backend: `GET /vcs/merge-requests/{id}/diff` with changes, commits, contributors and the conflicts against the target as it is now — route: inline — status: done — commits: c9d50b0 + live conflicts
- T4 (S1, S2, S3) web: commit detail with diff and authors in Historial — route: inline — status: done (compare two commits is not built: needs a field-level compare endpoint, offered as a follow-up)
- T5 (S2, S3) web: PR diff, commits, contributors and live conflicts in Revisiones — route: inline — status: done
- T6 (S1-S3) help text (done), deploy to the AWS instance and verify with two users in ego-lite — route: inline — status: pending

## Log

- L1 (2026-10-27) user: "Se puede ver por diferentes commits como en github?\nAdemás falta añadir las diff para un PR y los commits"
- L2 (2026-10-27) user: "Además que en los commits se vea el autor/autores."
- L3 (2026-10-27) context: today `GET /vcs/commits/{id}` and `GET /vcs/repo/diff` return only ADDED/REMOVED/MODIFIED with the element name (no field detail); the merge-request panel shows only conflicts; the history list shows the committer only. Open question to the user (structured text diff vs visual canvas diff) answered by assumption A1 until they say otherwise.
- L4 (2026-10-27) finding: a commit stores one author and nothing links it to the model revisions it covers (`model_version.created_by_user_id` has no FK to `vcs_commit`), so "everyone who changed the model since the parent commit" is not computable without a migration. Decision: phase 1 without migration (A2 revised); phase 2 stays optional.
- L5 (2026-10-27) evidence: `SnapshotDiffTest` 6/6, `VcsVersioningApiTest` 43/43 (4 new API tests), `VcsMergeDiffTest` 8/8. The first full run had 4 mobile-test errors (`Container startup failed for image postgres:16-alpine`) caused by running several maven builds at once; the three classes pass alone (23/23). A clean full run is pending at the end.
- L6 (2026-10-27) evidence: backend `VcsVersioningApiTest` 44/44; web `npm test` 743 passed (4 skipped before this work), `npm run lint` and `npm run build` clean; help test extended.
