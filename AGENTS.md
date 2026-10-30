# RelayOps development instructions

- Follow docs/spec.md, docs/tasks.md and docs/checklist.md. Label proposals and unfinished capabilities accurately; docs/product-directions.md is a proposal, not an implemented permission contract.
- The user authorized automatic Git commits and pushes on 2026-10-30: after completing, reviewing and appropriately validating a batch, commit it and push to the configured origin/current branch without asking again. Report the commit and any actual push failure. Preserve remote changes; do not force-push.
- Keep real credentials, ops/.env, artifacts, database contents and build/dependency outputs out of commits. Review the staged files before each push.
- Scope checks to the change: reuse recorded checks for unchanged application code, run relevant checks for new behavior, and report what was actually executed.
- Keep migrations V1/V2/V3 immutable once deployed; append migrations for schema changes. Preserve workspace authorization and SENT/UNKNOWN boundaries when adding features.
