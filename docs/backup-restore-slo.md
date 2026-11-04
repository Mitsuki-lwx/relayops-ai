# 备份、恢复与元数据保留

## 当前边界
本机已完成逻辑备份/恢复演练，但尚未声明生产 RPO/RTO 或跨机灾难恢复。`ops/.env` 主密钥、MySQL、RabbitMQ 数据卷和接收端 SQLite 需分别保留；丢失主密钥无法解密旧载荷。

## 演练命令
```powershell
node scripts/test-backup-restore.mjs
node scripts/metadata-retention-inventory.mjs --database relayops --days 30 --json
```

演练只从`relayops_test`备份到新建`relayops_restore_时间戳`库，比较完整有序SQL/密文hash后删除目标库；不写主库。最新本机结果（2026-11-04）：27表、779,666字节、备份976ms、恢复7,037ms、再导出455ms、总计8,680ms；这仅是单机逻辑恢复观测，不是生产SLO。

## SLO口径
- **RPO**：最近一次成功备份的时间点；当前没有 binlog/PITR 连续保护，备份间隔由运维决定。
- **RTO**：从确认备份可用到应用可只读核对的时间；本次本机逻辑恢复约8.7秒，不含停机决策、跨机传输、DNS/TLS、主密钥恢复和接收端去重库。
- 备份文件包含账号密码hash、业务密文和预算/审计元数据，必须按敏感资料保存；不提交Git/Release。

## 30天元数据
`metadata-retention-inventory.mjs`只读统计已终止且超过指定天数的任务及依赖行（结果、尝试、预算分录、幂等、Outbox、目的地、交付/轮次/尝试、子任务、审计）。当前**不执行删除**：删除会破坏预算/幂等/交付审计链，需先明确保留字段和用户授权；本轮不把盘点当清理完成。

## 恢复步骤
1. 暂停新受理，等待活跃责任终态；UNKNOWN 保留。
2. 备份并校验 `.sha256`，停止应用。
3. 新建独立空库导入，不改主库；核对Flyway、表数、行数、密文hash、预算/尝试/交付关系。
4. 先关闭 worker/consumer/relay/recovery/retention，只读核对后再启用执行器。
5. 接收端去重库和密钥同步恢复；SENT 无确定结果保持 UNKNOWN/UNCERTAIN。

参考：`docs/operations.md`、`docs/verification.md`。限制：未验证跨机、磁盘满、网络分区、备份加密和物理擦除。
