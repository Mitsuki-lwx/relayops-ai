# Tasks

## 1. 契约与后端
- [x] 1.1 更新spec/checklist/ADR，确认双输入、结果版本和影响边界。
- [x] 1.2 DocumentInput组合输入/双前缀段落/拆分；CreateJob可选inputTextB。
- [x] 1.3 DocumentResult `diff`结构、增删改双侧引用/影响项校验；旧序列化不变。
- [x] 1.4 TaskType/JobService受理/幂等/重跑/读取用途映射；无迁移。
- [x] 1.5 ModelGateway/Fake独立提示/profile，单次调用与输出上限。
- [x] 1.6 真实数据库验证结构、重跑、撤权、预算、回调与旧用途兼容。

## 2. 工作台与交付
- [x] 2.1 双文本框、用途切换、待确认草稿恢复和配置竞态。
- [x] 2.2 变化/影响/双侧证据视图与安全Markdown报告。
- [x] 2.3 三档浏览器、撤权/迟到/VIEWER/恶意内容负例。
- [x] 2.4 隔离发布包及少量真实双版本文本验收。
- [x] 2.5 自审、凭据扫描、OpenSpec归档、提交推送。
