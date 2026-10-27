# VCS diffs for commits and pull requests, with authors

Objective: GitHub-like browsing of history with field-level diffs for commits and merge requests (PR), showing who authored each commit.
Branch: feat/vcs-diffs-and-authors · Delivery: ask-on-risk · Test runner: `mvn -o test` (backend), `npm test` in `web/` (frontend).

## Specs

- S1: "Se puede ver por diferentes commits como en github?" — browse the history commit by commit (pick a commit, see its detail, compare two commits).
- S2: "falta añadir las diff para un PR y los commits" — a diff for each commit and for each merge request (PR).
- S3: "que en los commits se vea el autor/autores" — each commit shows its author(s), in the history list and in the commit detail, and the PR shows who contributed.

Assumptions (not requested verbatim, marked as assumptions):
- A1: the diff starts as a structured, field-level text diff (before → after, attributes and operations included). A visual diff on the canvas is stage 2 unless the user asks for it first.
- A2: "autores" = every user who contributed changes between the parent commit and the commit (taken from the per-revision author), not only who pressed "Confirmar cambios". The working copy is shared by the whole project, so those can differ.
- A3: a PR diff = what the source branch adds since the merge base (what GitHub shows), next to the conflict list.

## Tasks

- T1 (S2) backend: field-level changes (extend `VcsResources.Change`; keep existing fields so current clients keep working) — route: inline — status: pending
- T2 (S3) backend: authors per commit in the commit list and detail — route: inline — status: pending
- T3 (S2, S3) backend: merge-request diff endpoint with contributors — route: inline — status: pending
- T4 (S1, S2, S3) web: commit detail with diff and authors in Historial, plus compare two commits — route: inline — status: pending
- T5 (S2, S3) web: PR diff and contributors in Revisiones — route: inline — status: pending
- T6 (S1-S3) help text/docs, deploy to the AWS instance and verify with two users in ego-lite — route: inline — status: pending

## Log

- L1 (2026-10-27) user: "Se puede ver por diferentes commits como en github?\nAdemás falta añadir las diff para un PR y los commits"
- L2 (2026-10-27) user: "Además que en los commits se vea el autor/autores."
- L3 (2026-10-27) context: today `GET /vcs/commits/{id}` and `GET /vcs/repo/diff` return only ADDED/REMOVED/MODIFIED with the element name (no field detail); the merge-request panel shows only conflicts; the history list shows the committer only. Open question to the user (structured text diff vs visual canvas diff) answered by assumption A1 until they say otherwise.
