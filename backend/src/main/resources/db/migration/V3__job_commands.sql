ALTER TABLE ai_job
    ADD COLUMN parent_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_job_parent_workspace FOREIGN KEY (workspace_id, parent_job_id) REFERENCES ai_job(workspace_id, id);

ALTER TABLE audit_event ADD COLUMN detail_json JSON NULL;
ALTER TABLE idempotency_record ADD COLUMN response_json JSON NULL;

-- Upgrade the successful acceptance representations created before command snapshots existed.
UPDATE idempotency_record i
JOIN ai_job j ON j.id = i.job_id AND j.workspace_id = i.workspace_id
SET i.response_json = JSON_OBJECT('jobId', j.id, 'status', 'QUEUED', 'version', 0,
    'payloadExpiresAt', DATE_FORMAT(j.payload_expires_at, '%Y-%m-%dT%H:%i:%s.%fZ'))
WHERE i.operation = 'CREATE_JOB';
ALTER TABLE idempotency_record MODIFY COLUMN response_json JSON NOT NULL;
