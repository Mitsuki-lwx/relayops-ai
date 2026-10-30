CREATE TABLE tenant (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    name VARCHAR(120) NOT NULL
) ENGINE=InnoDB;

INSERT INTO tenant(id, name) SELECT id, CONCAT(name, ' 所属公司') FROM workspace;
ALTER TABLE workspace ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin;
UPDATE workspace SET tenant_id = id;
ALTER TABLE workspace MODIFY tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE (tenant_id, id), ADD FOREIGN KEY (tenant_id) REFERENCES tenant(id);

CREATE TABLE tenant_member (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    FOREIGN KEY (user_id) REFERENCES user_account(id),
    CHECK (role IN ('ADMIN','MEMBER'))
) ENGINE=InnoDB;
INSERT INTO tenant_member(tenant_id, user_id, role, active)
SELECT w.tenant_id, m.user_id, CASE WHEN m.role = 'OWNER' AND m.active THEN 'ADMIN' ELSE 'MEMBER' END, m.active
FROM workspace_member m JOIN workspace w ON w.id = m.workspace_id;

CREATE TABLE organization_unit (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    parent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES organization_unit(tenant_id, id),
    CHECK (kind IN ('DEPARTMENT','TEAM'))
) ENGINE=InnoDB;

CREATE TABLE organization_unit_member (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    unit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, unit_id, user_id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES organization_unit(tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_member(tenant_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE workspace_unit_grant (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    unit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, unit_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspace(tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES organization_unit(tenant_id, id),
    CHECK (role IN ('OPERATOR','VIEWER'))
) ENGINE=InnoDB;

CREATE TABLE organization_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) NOT NULL,
    detail_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (CURRENT_TIMESTAMP(3)),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
) ENGINE=InnoDB;
