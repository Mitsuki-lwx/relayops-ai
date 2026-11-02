# Design

## Context

动机见proposal。当前Spring Boot/JDBC/RabbitMQ承担任务责任，Vue/Vite为开发入口，LocalSeed仅local profile执行。组织审计增量未完成真实库验证。2026-11-02实测Windows排除3307–3406，原MySQL容器停止，卷仍在。

## Goals / Non-Goals

交付可信宿主机上的单机1.0，保留可配置真实模型及既有可靠性。发布包包含静态前端和可执行JAR，无运行期Node/Maven依赖。公网入口、TLS终止、多实例会话、SSO、公司计费、RAG和第二任务类型不纳入本版本。

## Decisions

1. Maven release profile把已构建frontend/dist作为static资源，使用同源Spring会话/CSRF；仅GET页面/静态资产/最小healthz公开，业务API继续默认拒绝。无需Nginx或第二容器。
2. release profile禁用local演示种子；宿主机命令以非Web模式初始化公司OWNER或添加MEMBER。密码只经环境/隐藏输入，单事务插入，重名拒绝，不重置已有密码。宿主机执行权限就是该命令的信任边界，不新增匿名API。
3. 用户进一步要求优先复用本机MySQL。实测MYSQL80服务运行在3306，原容器已临时恢复13307并保留原卷。连接及备份支持原生mysql/mysqldump，Docker MySQL为可选开发/CI依赖；切换前核对管理员凭据、版本和数据，不覆盖已有本机数据库，不删除Windows保留项。
4. healthz仅暴露版本和状态，数据库不可用返回503；不返回连接地址/模型配置。Broker暂不可用仍可有界受理，健康语义明确不冒充消息端到端成功。
5. 依赖镜像继续摘要固定。包使用白名单文件集合、版本和SHA256清单，拒绝夹带.env、库数据、日志和测试材料。CI跑隔离数据库/Fake消息与浏览器，真实模型历史证据单列，不把Fake视为最新供应商验收。
6. 备份以MySQL单事务逻辑导出为基础；运维说明要求停止新受理及等待活跃责任终态，再停应用保存数据库和独立密钥。恢复演练只使用新建专用库，比较表数据/加密hash；不覆盖主库，不重放UNKNOWN。

## Risks / Trade-offs

- 单机故障/会话丢失 → 文档明确重启后重新登录；数据库事实和原幂等键恢复，单副本部署。
- 本地用户初始化为高权限宿主操作 → 不开放HTTP入口，禁止覆盖已有身份，凭据不打印。
- 静态资源错误放行可能扩大权限 → 精确GET匹配并以真实HTTP验证匿名API401、CSRF403、VIEWER正文403。
- 旧依赖数据/模型凭据必须保持 → 仅读必要安全字段和hash；新验证在专用库与Fake模式运行。

## Migration Plan

先保存容器/卷标识并对比13307重建后MySQL数据，再完成组织审计；新增发布功能不改V1–V7。通过CI等价验证、JAR实际启动和恢复演练后发布v1.0.0。回退时停应用并替换上一JAR/前端版本，保留数据和密钥，不运行Flyway clean或删除卷。
