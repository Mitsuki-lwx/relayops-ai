CREATE TABLE user_account (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE workspace (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE workspace_member (
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (workspace_id, user_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (user_id) REFERENCES user_account(id),
    CHECK (role IN ('OWNER', 'OPERATOR', 'VIEWER'))
) ENGINE=InnoDB;

CREATE TABLE application (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id)
) ENGINE=InnoDB;

CREATE TABLE budget_account (
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accepted_date DATE NOT NULL,
    daily_limit INT NOT NULL DEFAULT 20,
    reserved INT NOT NULL DEFAULT 0,
    spent INT NOT NULL DEFAULT 0,
    uncertain INT NOT NULL DEFAULT 0,
    PRIMARY KEY (workspace_id, accepted_date),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    CHECK (reserved >= 0 AND spent >= 0 AND uncertain >= 0),
    CHECK (reserved + spent + uncertain <= daily_limit)
) ENGINE=InnoDB;

CREATE TABLE ai_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_cipher MEDIUMBLOB NOT NULL,
    input_hash CHAR(64) NOT NULL,
    accepted_date DATE NOT NULL,
    model_profile VARCHAR(40) NOT NULL DEFAULT 'fake-extract-v1',
    prompt_version VARCHAR(40) NOT NULL DEFAULT 'extract.v1',
    schema_version VARCHAR(40) NOT NULL DEFAULT 'document.extract.result.v1',
    output_language VARCHAR(10) NOT NULL,
    error_code VARCHAR(40),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    queue_expires_at DATETIME(3) NOT NULL,
    payload_expires_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, application_id) REFERENCES application(workspace_id, id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id),
    FOREIGN KEY (workspace_id, accepted_date) REFERENCES budget_account(workspace_id, accepted_date),
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
    INDEX job_ready (status, created_at),
    INDEX job_lease (status, lease_until),
    INDEX job_list (workspace_id, created_at, id)
) ENGINE=InnoDB;

CREATE TABLE budget_entry (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accepted_date DATE NOT NULL,
    state VARCHAR(16) NOT NULL,
    FOREIGN KEY (workspace_id, job_id) REFERENCES ai_job(workspace_id, id),
    FOREIGN KEY (workspace_id, accepted_date) REFERENCES budget_account(workspace_id, accepted_date),
    CHECK (state IN ('RESERVED','SPENT','UNCERTAIN','RELEASED'))
) ENGINE=InnoDB;

CREATE TABLE ai_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    sent_at DATETIME(3),
    completed_at DATETIME(3),
    sent_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN sent_at IS NOT NULL THEN job_id ELSE NULL END) STORED,
    UNIQUE (sent_job_id),
    FOREIGN KEY (workspace_id, job_id) REFERENCES ai_job(workspace_id, id),
    CHECK (status IN ('PREPARED','SENT','SUCCEEDED','FAILED','UNKNOWN','ABORTED'))
) ENGINE=InnoDB;

CREATE TABLE ai_result (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_cipher MEDIUMBLOB NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    FOREIGN KEY (workspace_id, job_id) REFERENCES ai_job(workspace_id, id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_record (
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (workspace_id, actor_id, operation, idempotency_key),
    FOREIGN KEY (workspace_id, job_id) REFERENCES ai_job(workspace_id, id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
) ENGINE=InnoDB;

CREATE TABLE audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id)
) ENGINE=InnoDB;
