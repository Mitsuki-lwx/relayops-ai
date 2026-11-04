# external-delivery Specification

## Purpose
在受控白名单内把结果交付到外部 HTTPS 接收端，并支持签名密钥轮换，同时保持原授权、签名、重试和回放责任。

## Requirements

### Requirement: Allowlisted external HTTPS destinations
系统SHALL只在部署白名单内创建外网HTTPS目的地；端点必须使用HTTPS和固定回调路径，浏览器不能提交任意URL。

#### Scenario: Non-allowlisted host
- **WHEN** OWNER提交不在白名单的主机、HTTP URL或含查询/片段的URL
- **THEN** 服务端拒绝创建，不产生目的地

#### Scenario: Allowlist revoked
- **WHEN** 已存在目的地的主机从配置白名单移除
- **THEN** 后续交付不得发送，既有责任按目录变更停止

### Requirement: Destination signing key rotation
OWNER SHALL在明确确认且目的地无活跃交付时轮换签名密钥；旧密钥立即失效并记录审计。

#### Scenario: Active delivery
- **WHEN** 目的地仍有PENDING/IN_FLIGHT/RETRY_WAIT交付
- **THEN** 轮换被拒绝且原密钥保持不变

#### Scenario: Successful rotation
- **WHEN** 无活跃交付且版本匹配
- **THEN** 生成新密钥、version递增、返回一次密钥并记录`DESTINATION_KEY_ROTATED`

### Requirement: Preserve delivery responsibility
外网交付MUST复用现有签名、幂等、TTL、目录冻结、重试和补投边界；轮换/停用不重跑模型。

#### Scenario: Retry after rotation
- **WHEN** 轮换后旧交付按原记录重试
- **THEN** 不重新调用模型，接收端需使用新密钥验证后续尝试
