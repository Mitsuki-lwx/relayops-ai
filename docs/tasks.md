# 1.0发布任务

权威实施进度见openspec/changes/release-relayops-1-0/tasks.md，共12项：范围、13307依赖恢复、组织审计、同源JAR/健康、账号初始化、打包校验、隔离全链路、CI、恢复演练、版本文档，最后为接入主流程与端到端发布。影响文件依次为ops/脚本/后端组织与identity/发布模块/pom/前端配置/.github/docs；前项验证后再发布。V1–V7不可修改。

# 第十四阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 公司审计边界与只读契约 | spec/checklist、ADR0012 | 无 | 当前ADMIN、安全六列 |
| 02 | 服务端查询与路由 | OrganizationService/Controller | 01 | tenant绑定、Long游标 |
| 03 | 真实数据库隔离负例 | OrganizationTest | 02 | 撤权/禁用/过滤/无副作用 |
| 04 | 独立审计面板 | OrganizationAuditPanel | 02 | 手动加载、筛选/分页 |
| 05 | 上下文与迟到保护 | 面板浏览器夹具 | 04 | 公司/隐藏/卸载/拒绝 |
| 06 | 三档布局与资料范围 | style、浏览器夹具 | 04 | 复用taste、纯文本 |
| 07 | 接入组织管理及主服务 | OrganizationPanel、README | 03/05/06 | 保持旧摘要接口 |
| 08 | 端到端核对与发布 | verification、checklist | 07 | 后端/浏览器/构建/推送 |

# 第十三阶段任务

| 编号 | 任务 | 文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 导出范围及权限契约 | spec/checklist、ADR0011 | 无 | 复用GET result及RESULT_READ |
| 02 | 安全Markdown格式化 | analysisReport | 01 | 固定章节、动态文本围栏、UTF-8 |
| 03 | 浏览器下载生命周期 | App | 02 | 新读取、上下文/TTL、URL释放 |
| 04 | 导出控件与说明 | App、style | 03 | 成功角色入口、本地副本边界 |
| 05 | 格式化负例 | report-export.spec | 02 | 恶意围栏/HTML、原文摘录、空集合 |
| 06 | 权限与竞争验证 | report-export.spec | 03/04 | 拒绝/TTL/迟到、重复点击 |
| 07 | 接入主流程 | 任务详情、README | 05/06 | 成功任务到重新授权到下载 |
| 08 | 端到端验证与发布 | 浏览器、verification、checklist | 07 | 真下载三档、构建/自审/推送 |
