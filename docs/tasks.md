# 第五阶段任务

用户确认按空间共享、组织单元显式授权；本批8项已实施并验证。历史与本批证据见 verification.md。

| 编号 | 任务 | 影响文件 | 依赖 | 参考 |
| --- | --- | --- | --- | --- |
| 01 | 明确组织授权和迁移 | docs/spec.md、checklist.md、adr/0005* | 无 | 用户选择、OWASP逐请求鉴权 |
| 02 | 追加组织模式与旧数据归属 | db/migration/V4*、identity/LocalSeed.java | 01 | 旧V1–V3，复合外键 |
| 03 | 统一公司/成员/授权检查 | identity/Access.java | 02 | require、me、事务租约边界 |
| 04 | 组织与空间管理事务 | organization/OrganizationService.java | 03 | 公司锁→直接成员→应用→预算→Job |
| 05 | 管理接口与审计 | organization/OrganizationController.java | 04 | CSRF、ADMIN和OWNER分别校验 |
| 06 | 组织管理与授权界面 | frontend/src/OrganizationPanel.vue、App.vue、style.css | 05 | 同公司多空间、部门/小组和成员 |
| 07 | 接入主流程 | identity、job、工作台、README.md | 06 | 小组授权→成员可见空间→任务→撤权 |
| 08 | 端到端验证 | 后端升级/组织测试、frontend/tests、verification.md | 07 | 跨公司/跨组/撤权/多组最高角色/幂等重放 |

后续另做公司注册与成员邀请、治理权限进一步细分、公司预算与模型密钥、回调和补投。
