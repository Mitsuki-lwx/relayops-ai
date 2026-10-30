# 0007 单条交付补投

2026-10-30，已实现并验证本机范围。依据原设计BR05/BR13/INV07/INV08，不扩大受控本机目录。

仅当前OWNER/OPERATOR且最新Run为DEAD、原载荷未过期、目的地启用及目录匹配时可预览。POST /deliveries/{id}/replay-plans携带expectedVersion与1–300字符原因，保存两分钟计划，绑定actor/空间/Delivery版本/目标版本及目录快照/payloadHash。预览不创建Run/Outbox、不发HTTP；计划生成响应不明时先刷新，旧计划自然过期。

POST /replay-plans/{planId}/confirm携带Idempotency-Key和acknowledgeDuplicateDelivery=true。当前授权→Delivery→计划→最新Run→目的地的当前锁定读；重查TTL、期限、版本、hash、目录/目标版本和启用状态。首次确认创建runNo+1的PENDING Run、独立Outbox，增加根版本、标记计划CONFIRMED、写审计与完整幂等响应，同事务提交。旧DEAD Run不复活；每轮仍最多6次/1小时。复用稳定deliveryId和原始密文/字节，不创建Job、不改AI额度。

同主体/空间/操作/键成功重放返回原接受快照，仍重查当前授权和载荷TTL；不同键消费已确认计划409。两份同版本计划并发确认最多一轮成功。目标停用再启用也会改变目标版本，必须重新预览。计划confirmedRunId仅为逻辑指针，Run保存同空间计划外键，避免反向删除环。

V6只追加replay_plan和Run的可空计划关联，V1–V5保持不变；沿用idempotency_record的新操作CONFIRM_DELIVERY_REPLAY，响应只含资源元数据。列表提供根版本、最新runNo及全部轮次/尝试。页面明确确认重复投递风险，保存原确认键与计划到按账号/空间/任务隔离的sessionStorage；网络不明/刷新后仅显式重试原意图，过期或状态冲突须重新预览。未确认预览仅内存保存。

不提供成功交付补投、批量补投、任意地址或替换正文。接收端必须保留稳定ID的去重事实，重投不承诺额外业务动作恰好一次。预览/确认在授权锁下串行，锁定授权前已撤销的请求拒绝；已提交外发无法追回。
