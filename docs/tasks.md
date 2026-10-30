# 第八阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 交付边界和协议 | spec/checklist、ADR0006 | 无 | 原设计BR03、BR10–12、签名规范 |
| 02 | V5与受控目的地 | migration、delivery服务 | 01 | 目录ID/空间OWNER/密钥加密 |
| 03 | 冻结及原子交付 | JobService/JobWorker、DeliveryStore | 02 | 创建及明确重跑、结果事务 |
| 04 | 独立消息与执行 | Outbox、DeliveryWorker/Consumer | 03 | 手动ACK、代次、总时限、恢复 |
| 05 | 独立接收端 | scripts/demo-receiver.py、注册脚本 | 04 | HMAC、SQLite原子去重 |
| 06 | 页面配置与时间线 | DestinationPanel、DeliveryPanel、App | 04 | taste既有样式、纯文本与权限 |
| 07 | 接入主流程 | 提交选择、任务详情、README | 05/06 | 提交→MQ→结果→回调 |
| 08 | 端到端验证 | 独立库/Broker测试、浏览器、verification | 07 | 500/500/200、确认丢失、无新模型调用 |