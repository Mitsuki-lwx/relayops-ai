# Spec Delta

## Purpose

用可重复验证和明确运维步骤支持单机1.0安装、备份与升级，证明发布包完整且不包含真实凭据，并保留任务调用与未知结果的责任边界。

## ADDED Requirements

### Requirement: Verifiable release artifact
交付 SHALL 含白名单运行文件、版本、SHA256清单、配置模板及运行/恢复说明，不包含真实.env、数据库、日志或依赖缓存。

#### Scenario: Build and verify
- **WHEN** 运行发布打包和完整性检查
- **THEN** 验证版本、内嵌前端和文件hash，改动包字节可被检查拒绝

### Requirement: Isolated acceptance and recovery
发布 SHALL 通过隔离Fake数据库/消息/浏览器检查及专用库备份恢复演练，记录实际结果，CI不得调用付费模型。

#### Scenario: Restore rehearsal
- **WHEN** 将逻辑备份恢复到新建演练库
- **THEN** 表与密文hash一致，主库和主密钥保持不变；UNKNOWN不自动重发

#### Scenario: Release gate fails
- **WHEN** 任一必需检查失败或未执行
- **THEN** 不标记该门禁完成，不把未验证版本声明为已发布
