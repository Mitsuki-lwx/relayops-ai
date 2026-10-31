# 第十二阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 范围与分页契约 | spec/checklist、ADR0010 | 无 | 原设计created_at/id倒序 |
| 02 | 历史查询与显式安全列 | JobService | 01 | LOCATE、状态白名单、limit+1 |
| 03 | 历史只读端点 | JobController | 02 | 当前授权及游标边界 |
| 04 | 真实库边界验证 | JobHistoryIntegrationTest | 03 | 超50、同时间、插入、隔离/撤权 |
| 05 | 历史筛选分页页面 | JobHistoryPanel、style | 03 | taste、已应用条件、清空/迟到保护 |
| 06 | 点击历史任务详情 | App | 05 | 原详情与正文授权 |
| 07 | 接入主流程 | 队列入口、README | 04/06 | 登录到历史到详情 |
| 08 | 端到端验证与发布 | 浏览器、verification、checklist | 07 | 三档、负例、只读对账、自审推送 |
