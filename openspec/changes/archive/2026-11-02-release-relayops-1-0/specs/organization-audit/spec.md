# Spec Delta

## Purpose

允许当前公司管理员核对组织、成员和显式空间授权的治理事件，支持安全过滤和历史分页，同时保持公司治理权限与空间正文访问权限分离。

## ADDED Requirements

### Requirement: Tenant-scoped safe audit
系统 SHALL 逐次验证启用账户及当前公司ADMIN，仅返回六项安全事件字段，不读取变更正文详情。

#### Scenario: Current administrator
- **WHEN** 当前有效公司ADMIN查询本公司组织审计
- **THEN** 返回事件ID字符串、操作类型、资源ID、操作者ID/当前用户名和时间

#### Scenario: Revoked or foreign user
- **WHEN** 账户禁用、公司成员撤销、跨公司或当前角色为MEMBER
- **THEN** 拒绝请求，且不新增空间/正文访问权

### Requirement: Stable bounded audit browsing
系统 SHALL 按ID倒序绑定公司/事件/资源条件，限制每页1至100且默认25，使用正Long字符串游标；读取无业务写入。

#### Scenario: Concurrent newer insertion
- **WHEN** 用户读取下一页前插入更高ID事件
- **THEN** 下一页继续查询原游标之前的记录，不重复较新事件

#### Scenario: Context changes
- **WHEN** 浏览器切公司、隐藏、离开视图或请求拒绝
- **THEN** 清除旧记录，迟到响应不重新填充
