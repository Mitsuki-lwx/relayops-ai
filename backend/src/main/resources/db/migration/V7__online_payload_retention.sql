ALTER TABLE ai_job
    MODIFY COLUMN input_cipher MEDIUMBLOB NULL,
    ADD COLUMN payload_deleted_at DATETIME(3),
    ADD INDEX job_payload_retention (payload_deleted_at, payload_expires_at, id);

ALTER TABLE ai_result
    MODIFY COLUMN result_cipher MEDIUMBLOB NULL,
    ADD COLUMN payload_deleted_at DATETIME(3);

ALTER TABLE delivery
    MODIFY COLUMN payload_cipher MEDIUMBLOB NULL,
    ADD COLUMN payload_deleted_at DATETIME(3),
    ADD INDEX delivery_payload_retention (payload_deleted_at, payload_expires_at, id);
