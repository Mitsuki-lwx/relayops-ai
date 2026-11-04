# ADR 0016：受控外网HTTPS回调与签名密钥轮换

## 状态
已接受（2026-11-04）。

## 背景
原本机回调目录只允许固定 `http://127.0.0.1:8091/relayops/events`，签名密钥只能读取不能轮换，限制了团队把结果交付到受控接收端。开放任意URL会把服务变成SSRF/数据外发代理。

## 决策
- 新增目录`https-allowlist-v1`，仅当`RELAYOPS_DELIVERY_HTTPS_HOSTS`配置非空时出现；白名单为逗号分隔DNS主机名，启动时校验，拒绝IP、localhost、单标签、端口和非法字符。
- 外网端点必须HTTPS、固定路径`/relayops/events`、无用户信息/查询/片段、端口443；创建时校验，发送前再次解析并拒绝环回/私网/链路本地/组播地址。重定向仍不跟随。
- 目录动态校验：白名单移除或端点变化后，旧目的地不再被领取，按`CATALOG_CHANGED`停止，不静默继续。
- OWNER可轮换签名密钥：要求确认和`expectedVersion`；若有PENDING/IN_FLIGHT/RETRY_WAIT交付则409；生成新32字节密钥、替换密文、version+1、审计`DESTINATION_KEY_ROTATED`并返回一次密钥。
- 不新增迁移；复用endpoint/catalog_id/secret_cipher/version。前端目录选择、端点输入和轮换按钮。

## 后果
- 实际第三方HTTPS证书交付未在本次端到端验证；验证覆盖创建/白名单/SSRF拒绝/本机签名与轮换。
- DNS重绑定仍可能绕过创建时检查；发送前解析降低风险，但生产应使用受控主机、网络出口策略和接收端验签。
- 轮换无旧密钥宽限窗口；有活跃交付时拒绝轮换，运维需先等待/停止接收端。
