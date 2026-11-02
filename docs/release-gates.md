# 1.0发布验收门禁

2026-11-02。功能与原生部署验收已完成；正在提交最终代码、等待CI并发布正式tag/附件，发布后再完成OpenSpec最后任务及归档。

| 门禁 | 实际证据 | 状态 |
| --- | --- | --- |
| OpenSpec | release-relayops-1-0四项能力，严格验证通过；11/12任务完成，剩发布本身 | 待发布归档 |
| 原生迁移 | MYSQL80/8.0.42，3306；新建目标库/专用账号，27表数据及密文逐表hash一致，原主密钥保留 | 通过 |
| 旧数据/容器 | 73任务/54结果保留，源容器停止且命名卷保留，RabbitMQ继续运行 | 通过 |
| 后端 | release-native-tests.log，120项，零失败/错误/跳过 | 通过 |
| 工作台 | release-native-browser-fixed.log，80项（79浏览器+1格式），零跳过；新建专用库/Fake | 通过 |
| 真实报告下载 | release-report.spec.ts，无业务响应拦截，CLI建立的OWNER提交Fake任务、持久化一次调用、新GET结果、UTF-8文件/引用核对 | 通过 |
| release profile/JAR | 原生专用库实际启动；无演示种子，真实CLI OWNER/MEMBER，匿名200/API401/CSRF403/非法参数422/缺资源404/幂等/隔离 | 通过 |
| 备份恢复 | release-native-restore-utc.log，27表新建恢复库有序SQL及密文hash一致，演练库完成后删除 | 通过 |
| 工具时区 | 原生CLI初始SYSTEM偏移28800秒，统一工具会话UTC后偏移0，全局SYSTEM保留；TTL竞争负例复验通过 | 通过 |
| 分发完整性 | 最终ZIP/15文件清单、JAR内嵌前端及字体；未列隐藏文件和字节篡改均拒绝 | 通过 |
| 主服务 | 18080健康UP/1.0.0、原生3306；旧账号登录、空间概览、公司ADMIN200/MEMBER403/跨公司404/非法422；用户模型配置保留 | 通过 |
| 主库副作用 | 12类主库业务事实/密文/密钥hash与启动前相同，0新增模型尝试，无真实供应商调用 | 通过 |
| 布局 | 原生1440/768/390通过，真实报告截图/文件已目视复核；taste lint0FAIL/3既有mono警告，复用既有样式原生测量 | 通过 |
| CI | e977faf的[41329199837](https://github.com/Mitsuki-lwx/relayops-ai/actions/runs/41329199837)通过，Linux产物下载后15文件及ZIP哈希核对通过；最终小增量另待CI | 待最终CI |
| 正式发布 | 凭据扫描、推送、v1.0.0、附件下载复核、OpenSpec归档 | 执行中 |

容器/MySQL8.4.7及Ubuntu/Java21/Node22/Chromium验证和原生8.0.42/Edge验证分别记录。真实供应商适配代码未变，复用原第七阶段真实成功证据；本批没有重新外发真实文档，不把Fake当作当前供应商效果或额度验收。

分发历史：33bf4e6的首轮CI虽通过，但Linux清单未覆盖隐藏.env.example（14项/实际15文件）。已复现并拒绝旧包；e977faf加入-Force及完整文件集合核对，第二轮CI产物15项全部通过。旧包未作为正式发布附件。历史失败与修复过程保留在progress.md。

框架用法核对官方[Spring Boot静态资源](https://docs.spring.io/spring-boot/reference/web/servlet.html)、[Spring Security请求授权](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)和[Maven资源目录](https://maven.apache.org/plugins/maven-resources-plugin/examples/resource-directory.html)。
