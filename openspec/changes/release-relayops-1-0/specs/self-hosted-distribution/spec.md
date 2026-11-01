# Spec Delta

## Purpose

提供无需开发服务器的单机自托管应用分发形式，将工作台和后端作为一个版本安装与运行，并为操作者提供不泄露配置的最小存活信号。

## ADDED Requirements

### Requirement: Same-origin packaged application
系统 SHALL 提供版本1.0.0的可执行JAR，内含本地字体及构建后的工作台；发布启动使用release profile，不自动创建演示用户。

#### Scenario: Fresh release startup
- **WHEN** 使用Java21、已配置MySQL/RabbitMQ和主密钥启动发布包
- **THEN** 同一18080端口提供登录页与业务API，不需要Node/Vite/Maven，不占用8080

#### Scenario: Anonymous access
- **WHEN** 匿名用户读取页面、静态资源或访问业务API
- **THEN** 页面可加载，业务API返回401；写入仍受CSRF保护

### Requirement: Minimal versioned health
系统 SHALL 提供无凭据的最小版本/数据库可用状态，不包含模型名称、连接地址或内部异常。

#### Scenario: Database unavailable
- **WHEN** 健康查询的数据库探测失败
- **THEN** 返回503和安全状态，不能声称模型或Broker链路健康
