# 1.0候选发布门禁

2026-11-02。这是候选验收记录，**尚未创建v1.0.0或正式Release**。用户要求优先使用本机MySQL，最终连接/迁移需要用户填写不入库的ops/mysql-native.cnf，不能因已有容器测试通过就宣称原生切换完成。

| 门禁 | 证据 | 状态 |
| --- | --- | --- |
| OpenSpec严格校验 | release-relayops-1-0四项能力与12项任务，validate --strict通过 | 通过 |
| 公司组织审计 | 新增4项真实数据库测试及13项夹具浏览器，当前ADMIN/撤权/字段/大ID分页 | 通过 |
| 后端规则/库/消息/初始化 | release-tests-adapted-fixed.log，120项0失败/错误/跳过 | 通过 |
| 完整工作台 | release-browser-all-fixed.log，79项通过（78浏览器+1格式），新建隔离库/Fake | 通过 |
| release profile实际JAR | release-packaged-final.log，无演示种子，真实CLI建立OWNER/MEMBER，匿名200/API401/CSRF403、非法参数422、缺资源404、Fake一次调用/幂等/隔离 | 通过 |
| 构建及完整性 | rc3 clean package，15文件manifest/hash（rc2篡改负例），故意篡改CHANGELOG被拒绝，恢复后通过 | 通过 |
| 接收端 | release-receiver.log，2项通过 | 通过 |
| 备份恢复 | release-restore-docker.log，27表完整有序SQL及密文hash一致，仅新建恢复库 | Docker通路通过；原生待验证 |
| 主库保持 | 对照第十三阶段基线12类业务/密文/密钥hash不变，paidCallsCreated=0 | 通过 |
| taste | 组织审计原生三档0FAIL/0WARN，lint0FAIL/3既有mono警告，三档图片已目视审阅 | 通过 |
| 本机MySQL | MYSQL80运行3306，客户端8.0.42；无可用管理员连接，空密码拒绝 | 等待私密配置 |
| CI | 已定义Ubuntu/Java21/Node22/真实MySQL与Rabbit/Fake浏览器/恢复/打包 | 待远程执行 |
| 原生主服务/发布 | 原数据库保留73任务/54结果，尚未切换本机库，未归档未完成任务 | 待完成 |

rc3采用clean package防止旧静态资源残留；新增测试运行器和原生URL适配已完成容器环境复验，原生本机连接仍待配置。真正发布前仍需重新生成最终白名单包，核对Git提交和附件hash；不上传SQL、配置或验收截图。

新增框架用法核对官方[Spring Boot静态资源](https://docs.spring.io/spring-boot/reference/web/servlet.html)、[Spring Security请求授权](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)和[Maven资源目录](https://maven.apache.org/plugins/maven-resources-plugin/examples/resource-directory.html)。真实供应商适配代码本批未变，复用原第七阶段真实成功证据；本批未重新外发真实文档，不声称当前供应商配额或效果已验收。
