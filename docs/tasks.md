# 第六阶段任务

按本机taste skill的app-screens流程重构，8项已完成并验证，保留现有业务契约。设计记录见frontend-design.md，验证见verification.md。

| 编号 | 任务 | 影响文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 盘点数据和主任务 | docs/frontend-design.md、spec.md、checklist.md | 无 | taste app-screens 2.1–2.3 |
| 02 | 抽取并选定风格 | frontend-design.md、artifacts/taste | 01 | framed-sheet，seed1361095215 |
| 03 | 外壳与字体 | App.vue、AppIcon.vue、style.css、public/fonts | 02 | app shell、IBM Plex与OFL |
| 04 | 任务列表和详情 | App.vue、style.css | 03 | 已载入范围、搜索/状态、手机返回 |
| 05 | 新建和组织独立视图 | App.vue、OrganizationPanel.vue | 04 | 不改提交/权限/原键语义 |
| 06 | 更新响应式与状态 | style.css、frontend/tests/visual.spec.ts | 05 | 1440/768/390、焦点、减少动态 |
| 07 | 接入主流程 | 工作台与既有浏览器回归 | 06 | 提交→状态→证据；取消/重跑/授权 |
| 08 | 端到端验证 | frontend/tests、verification.md | 07 | 构建、taste lint/inspect、实际截图与自审 |
