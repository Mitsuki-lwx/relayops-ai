# 第十阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 在线保留范围与锁序 | spec/checklist、ADR0008 | 无 | BR14/RB06 |
| 02 | V7可空密文与删除标记 | migration、升级测试 | 01 | V1–V6不改 |
| 03 | 到期Job执行与恢复边界 | JobWorker | 02 | SENT前TTL、UNKNOWN不重发 |
| 04 | 到期交付恢复 | DeliveryWorker | 02 | 不依赖MQ领取结束责任 |
| 05 | 事务分批清理 | PayloadRetention | 03/04 | 终态重查、标记/审计/回滚 |
| 06 | 正文访问防线 | JobService、ReplayService、错误文案 | 05 | 410、删除标记、当前权限 |
| 07 | 接入调度与运行说明 | application、README | 05/06 | 一分钟/每类25、保留边界 |
| 08 | 集成与发布 | 隔离测试、verification | 07 | 并发/回滚/迟到/迁移、自审推送 |