# 0006 受控本机结果交付

2026-10-30，已实现并完成本机范围验证，依据原设计BR03/BR10–12及relay.webhook.v1。证据见verification.md；不表示生产回调全部能力完成。

首批目录demo-local-v1仅固定http://127.0.0.1:8091/relayops/events；维护者可通过启动配置改本机端口用于隔离测试，浏览器不能提交地址。冻结快照与当前目录不一致则DEAD。外网HTTPS目录/出站安全另批，不宣称支持任意URL。

OWNER创建/启停空间目的地及经显式审计接口读取32字节随机签名密钥；OPERATOR选择，VIEWER只看交付元数据。AES-GCM保存密钥，keyId为目的地ID，不提供轮换。任务最多3个不同目标，排序参与指纹；明确重跑沿用来源目标并重查启用状态，界面说明。

V5六表：destination、job_destination、delivery、delivery_run、delivery_attempt、delivery_outbox。独立交付Outbox保留AI原外键与协议，复用既有发布确认/return机制，两种kind/路由/关联分别验证。结果/SUCCEEDED/Delivery/首Run/Outbox同事务；旧无目标任务不回填。

结果信封不含输入正文，原始UTF8字节只生成一次，SHA256与密文持久化，TTL沿用原Job。HMAC输入严格为v1\n{Unix秒}\n{deliveryId}\n{bodySHA256}；六个X-Relay-*头按原设计，签名小写hex。重试更新Attempt/时间戳/签名，正文和deliveryId不变。

每Run最多6次、总预算1小时，请求总时限5秒、租约30秒。2xx成功，408/429/5xx/网络未知可重试，其余非2xx/停用/目录变化/TTL/预算耗尽DEAD。退避1/5/30/120/600秒加0–20%抖动；429响应的合法Retry-After取较晚，不越预算。禁跳转、不保存响应正文。租约过期Attempt为UNKNOWN，旧执行器不能回写。回调不修改AI状态、额度或再次调用模型。

锁序：授权/公司→应用/额度→Job→目的地共享锁（受理）；交付候选只定位，再Delivery→Run→目的地共享当前读→Attempt→Outbox。目的地管理只锁授权与目标，不反向锁业务。Outbox不反向进入业务；事务内无网络。

独立Python接收端绑定127.0.0.1，SQLite原子保存deliveryId/hash/已接收结果，同ID同hash返回既有2xx，不同hash拒绝。五分钟时间窗、常量时间比较，演示注册密钥/DB在忽略的artifacts中。两个系统无共享事务，不承诺端到端恰好一次。

本批不做人工补投、正文读取API、外部业务副作用、物理TTL清理、生产目录与轮换；Run结构保留后续扩展。
