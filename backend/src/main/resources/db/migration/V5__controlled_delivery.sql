CREATE TABLE destination (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    catalog_id VARCHAR(40) NOT NULL,
    endpoint VARCHAR(200) NOT NULL,
    secret_cipher BLOB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id)
) ENGINE=InnoDB;

CREATE TABLE job_destination (
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    catalog_id VARCHAR(40) NOT NULL,
    endpoint VARCHAR(200) NOT NULL,
    name VARCHAR(80) NOT NULL,
    PRIMARY KEY (job_id, destination_id),
    FOREIGN KEY (workspace_id, job_id) REFERENCES ai_job(workspace_id, id),
    FOREIGN KEY (workspace_id, destination_id) REFERENCES destination(workspace_id, id)
) ENGINE=InnoDB;

CREATE TABLE delivery (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    catalog_id VARCHAR(40) NOT NULL,
    endpoint VARCHAR(200) NOT NULL,
    destination_name VARCHAR(80) NOT NULL,
    payload_cipher MEDIUMBLOB NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_expires_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, id),
    UNIQUE (job_id, destination_id),
    FOREIGN KEY (workspace_id, job_id) REFERENCES ai_job(workspace_id, id),
    FOREIGN KEY (workspace_id, destination_id) REFERENCES destination(workspace_id, id)
) ENGINE=InnoDB;

CREATE TABLE delivery_run (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_no INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    dispatch_generation BIGINT NOT NULL DEFAULT 1,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    dispatched_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    deadline_at DATETIME(3) NOT NULL DEFAULT (DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR)),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until DATETIME(3),
    error_code VARCHAR(40),
    UNIQUE (workspace_id, id),
    UNIQUE (delivery_id, run_no),
    FOREIGN KEY (workspace_id, delivery_id) REFERENCES delivery(workspace_id, id),
    CHECK (status IN ('PENDING','IN_FLIGHT','RETRY_WAIT','SUCCEEDED','DEAD')),
    CHECK (attempt_count BETWEEN 0 AND 6),
    INDEX delivery_due (status, next_attempt_at),
    INDEX delivery_lease (status, lease_until)
) ENGINE=InnoDB;

CREATE TABLE delivery_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'STARTED',
    key_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    http_status INT,
    error_code VARCHAR(40),
    started_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    completed_at DATETIME(3),
    UNIQUE (run_id, attempt_no),
    FOREIGN KEY (workspace_id, run_id) REFERENCES delivery_run(workspace_id, id),
    CHECK (status IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB;

CREATE TABLE delivery_outbox (
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
    FOREIGN KEY (workspace_id, aggregate_id) REFERENCES delivery_run(workspace_id, id),
    CHECK (dispatch_generation >= 1),
    CHECK (status IN ('NEW','CLAIMED','SENT')),
    INDEX delivery_outbox_ready (status, next_publish_at, id),
    INDEX delivery_outbox_capacity (workspace_id, status, id)
) ENGINE=InnoDB;
