# Design

## Context
目的地目录原本固定本机 HTTP；endpoint 已存储在 destination/job_destination/delivery 中，签名密钥加密在 secret_cipher。外网交付必须避免把工作台变成任意 URL/SSRF 代理。

## Decisions
- 新增目录 `https-allowlist-v1`，仅当 `RELAYOPS_DELIVERY_HTTPS_HOSTS` 非空时出现。白名单为逗号分隔的 DNS 主机名；不接受 IP 字面量、localhost、用户信息、查询、片段或非443端口。
- 端点必须是 `https://<allowlisted-host>/relayops/events`；创建时校验，发送前再次校验主机解析结果，拒绝环回/私网/链路本地/组播地址。DNS 重绑定仍是残余风险，运营方应使用受控接收端。
- `DestinationService.current` 每次动态检查目录与白名单；白名单移除或主机变化使旧目的地 DEAD，不静默继续。
- 签名密钥轮换：OWNER + `expectedVersion` + 明确确认；目的地无活跃 delivery_run 时生成新32字节密钥、替换密文、version+1、写 `DESTINATION_KEY_ROTATED` 审计并返回一次密钥。旧密钥立即失效；接收端需同步更新。
- 不新增数据库迁移；沿用 endpoint/catalog_id/secret_cipher/version。
- 前端目录选择、外部 endpoint 输入和轮换按钮；仍只在确认后显示密钥。

## Risks / Trade-offs
- 实际外网 HTTPS 交付未在本次用第三方证书端到端验证；只验证创建、白名单、SSRF拒绝和本机签名路径。
- 密钥轮换不保留旧密钥宽限窗口；有活跃交付时拒绝轮换。接收端更新与平台轮换的时序由运维控制。
- 白名单是部署级控制，不是租户自定义任意 URL。
