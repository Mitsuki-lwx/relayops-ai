# 第七阶段任务

本阶段8项已完成实现和验证。启用用户真实配置，自拟文档验收发现不兼容后先以本机协议用例修复，复验使用明确的新关联任务。

| 编号 | 任务 | 影响文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 配置安全检查与启用 | 本地ops/.env、启动脚本使用方式 | 无 | 不输出密钥，去除Fake覆盖 |
| 02 | 真实链路验收记录 | artifacts/stage7-*、verification.md | 01 | 自拟文本、一次SENT、失败保留 |
| 03 | 修复成功信封及空工具字段兼容 | job/ModelGateway.java、ModelGatewayTest.java | 02 | 实测success/data包装、可选工具元数据；失败信封及内部非法结果仍拒绝 |
| 04 | 固定安全诊断 | job/ModelGateway.java | 03 | 不含供应商正文与异常文本 |
| 05 | 结果与依据完整展示 | frontend/src/ResultView.vue、App.vue | 03 | summary、keyPoints、constraints、unknowns |
| 06 | 安全错误解释 | frontend/src/jobErrors.ts、App.vue | 04 | 现有错误码及用量/重跑边界 |
| 07 | 接入主流程 | 工作台、真实关联任务与README.md | 05/06 | 明确重跑，不复活来源，不自动重发 |
| 08 | 端到端验证 | 后端回归、浏览器响应夹具、verification.md | 07 | 原任务只读核对、无批量真实调用 |
