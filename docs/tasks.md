# 1.0发布任务

权威实施进度见[OpenSpec归档](../openspec/changes/archive/2026-11-02-release-relayops-1-0/tasks.md)，12/12已完成，包括本机3306迁移、组织审计、同源JAR/健康、账号初始化、打包校验、隔离全链路、CI、恢复演练、版本文档及端到端发布。[v1.0.0](https://github.com/Mitsuki-lwx/relayops-ai/releases/tag/v1.0.0)附件已重新下载核对，详见[发布门禁](release-gates.md)。V1–V7保持不变。

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

# Langfuse观测任务
详见openspec/changes/archive/2026-11-02-add-langfuse-tracing/tasks.md：配置/SDK→阶段与usage→worker/回调接入主流程→故障与真实库验证→本机读回端到端、自审与推送。影响TaskTracing、ModelGateway、JobWorker、DeliveryWorker及配置/测试/运维。

# 真实模型验收
已在独立环境完成三条合成样本的真实模型、幂等、引用、签名回调、Langfuse读回及浏览器报告下载。[验收数据与局限](real-model-verification.md)。本批无产品代码或规范行为变化，沿用现有OpenSpec契约。

# 需求评审任务（已完成，2026-11-03）
OpenSpec add-requirements-review共10项已完成：契约→TaskType/DocumentResult→ModelGateway/Fake→JobService/Controller→真实库负例→用途/竞态恢复→评审组件/analysisReport→浏览器三档→接入主流程→端到端验收和推送。V1–V7未修改，使用既有schema_version冻结用途；137后端、88浏览器及4条成功真实评审（另2次失败驱动独立输出上限）通过，详见[验证记录](verification.md)。


# 需求评审固定评测集（2026-11-03）
20个中文合成样本覆盖干净对照、GAP、歧义、冲突、注入、单位等价和多段长文本；确定性评分器检查结构/引用/必找问题/禁止文字，不调用模型。评分器10项单元测试和数据集校验已接入CI；单次真实运行20/20，三次重复运行稳定16/20、单次通过56/60（93.33%），暴露2次UNKNOWN、1次截断和1次语义漏项；基线与局限见[验证记录](verification.md)。


# 空间每日额度策略（2026-11-03）
V8追加workspace策略/版本列；OWNER在部署硬上限内调整每日额度，预算行按天快照；当天降低不得低于已占用，变更审计，GET/PATCH接口及运行概览编辑。后端138项通过，浏览器套件见verification；公司总额度/按用户额度仍为后续。


# 文档版本对比与影响分析（已完成，2026-11-03）
OpenSpec add-version-diff共10项已完成：组合输入/双前缀引用→结果校验→受理/重跑/读取→模型/Fake→真实库负例→双文本框/结果/报告→三档浏览器→2例真实双版本文本→自审推送。145后端/97浏览器通过；真实首次暴露data-only包裹和before/after字符串数组，修复后成功。不新增V9迁移，V1–V8不变；旧EXTRACT/REVIEW请求与结果保持。

# 版本对比固定评测集（2026-11-03）
16个合成样本、确定性评分器、受控运行器、三次真实运行和8例人工复核已完成。v1 48任务全成功、稳定15/16；v2 47/48成功、稳定15/16、重复通过97.92%，注入3/3通过但一次重排输出无效。同供应商qwen3.8-flash对照完成（评审17/20、版本v2 16/16单次）。基线见`eval/version-diff/baselines/`。

# 同供应商模型对照（2026-11-03）
限定`qwen/qwen3.8-flash`；同供应商/提示/固定集单次运行。评审17/20 vs deepseek20/20，版本14/16 vs 15/16；qwen延迟/Token更高且有超时、无效输出和重排误报。qwen未做三次重复，不选为默认模型。

# 受控外网HTTPS回调与密钥轮换（已完成，2026-11-04）
OpenSpec add-external-delivery：白名单URL/SSRF校验→动态目录/冻结→轮换API/活跃交付保护→前端目录/轮换→151后端/98浏览器通过→自审推送。无迁移；原本机目录和交付契约保持。真实第三方证书端到端交付未验证。

# 备份恢复SLO与30天元数据盘点（2026-11-04）
新增恢复演练耗时报告（27表/8.68秒本机逻辑恢复）和只读元数据盘点脚本；未执行删除。SLO边界、RPO/RTO口径和恢复步骤见`docs/backup-restore-slo.md`。删除式30天清理仍需明确保留字段和用户授权。
