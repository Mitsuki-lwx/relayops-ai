ALTER TABLE workspace
    ADD COLUMN daily_limit INT NOT NULL DEFAULT 20,
    ADD COLUMN budget_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_workspace_daily_limit CHECK (daily_limit BETWEEN 1 AND 10000);
