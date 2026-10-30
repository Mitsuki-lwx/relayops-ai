CREATE TABLE replay_plan (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_version BIGINT NOT NULL,
    destination_version BIGINT NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    catalog_id VARCHAR(40) NOT NULL,
    endpoint VARCHAR(200) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'READY',
    expires_at DATETIME(3) NOT NULL DEFAULT (DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 2 MINUTE)),
    confirmed_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, delivery_id) REFERENCES delivery(workspace_id, id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id),
    CHECK (status IN ('READY','CONFIRMED')),
    INDEX replay_delivery (workspace_id, delivery_id)
) ENGINE=InnoDB;

ALTER TABLE delivery_run
    ADD COLUMN replay_plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD CONSTRAINT fk_run_replay_plan FOREIGN KEY (workspace_id, replay_plan_id) REFERENCES replay_plan(workspace_id, id),
    ADD UNIQUE (replay_plan_id);
