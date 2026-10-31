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
