# 第九阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 补投边界与确认协议 | spec/checklist、ADR0007 | 无 | BR13两分钟计划 |
| 02 | V6计划与轮次关联 | migration、测试清理 | 01 | 不改V1–V5 |
| 03 | 预览、确认及幂等审计 | ReplayService、Controller | 02 | 当前授权、根版本、TTL及目标绑定 |
| 04 | 全轮次查询 | DeliveryStore | 03 | 旧轮次/尝试保留 |
| 05 | 补投页面与刷新恢复 | DeliveryPanel、App | 04 | 账号/空间隔离的原确认意图 |
| 06 | 并发及权限故障验证 | DeliveryIntegrationTest | 03 | 两计划竞争、撤权/过期/响应丢失 |
| 07 | 接入主流程 | 详情、README | 05/06 | DEAD预览→确认→新轮次→接收端 |
| 08 | 端到端与发布 | 浏览器、verification | 07 | 原ID/字节、去重、模型一次，自审推送 |