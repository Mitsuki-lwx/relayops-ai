ALTER TABLE ai_job ADD COLUMN dispatch_generation BIGINT NOT NULL DEFAULT 1;

CREATE TABLE outbox_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispatch_generation BIGINT NOT NULL,
    trace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'NEW',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    claim_until DATETIME(3),
    publish_attempts INT NOT NULL DEFAULT 0,
    next_publish_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    published_at DATETIME(3),
    last_error VARCHAR(40),
    UNIQUE (aggregate_id, dispatch_generation),
    FOREIGN KEY (workspace_id, aggregate_id) REFERENCES ai_job(workspace_id, id),
    CHECK (dispatch_generation >= 1),
    CHECK (status IN ('NEW','CLAIMED','SENT')),
    INDEX outbox_ready (status, next_publish_at, id),
    INDEX outbox_lease (status, claim_until),
    INDEX outbox_capacity (workspace_id, status, id)
) ENGINE=InnoDB;

CREATE TABLE quarantine_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    body_hash CHAR(64) NOT NULL,
    error_code VARCHAR(40) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3))
) ENGINE=InnoDB;

-- Preserve already accepted queued responsibilities when upgrading the first slice.
INSERT INTO outbox_event(id, workspace_id, aggregate_id, dispatch_generation, trace_id)
SELECT UUID(), workspace_id, id, dispatch_generation, UUID() FROM ai_job WHERE status = 'QUEUED';
