# Spec Delta

## Purpose

为可信宿主机管理员提供真实团队账号的初始化方式，使发布版本不依赖演示种子或公开注册，同时继承已有公司的部门、小组与显式空间授权规则。

## ADDED Requirements

### Requirement: Explicit host provisioning
系统 SHALL 允许宿主机非Web命令创建新公司及OWNER/ADMIN，或向已有公司添加MEMBER；密码经环境或隐藏输入传入，明文不写日志或命令参数。

#### Scenario: New company
- **WHEN** 有效新用户名、强密码和公司名称被显式提交
- **THEN** 单事务建立账户、公司、空间及OWNER/ADMIN关系，并只输出安全标识

#### Scenario: Existing company member
- **WHEN** 宿主机向现有公司添加新成员
- **THEN** 该账户成为MEMBER但未自动获得任何空间权限，后续由现有组织授权管理

#### Scenario: Duplicate or invalid input
- **WHEN** 用户名已存在、密码无效或公司不存在
- **THEN** 拒绝且不覆盖原密码、不产生部分公司或成员
