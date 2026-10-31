# 验证记录

## 第十阶段到期在线载荷清理

功能提交814bfff已推送origin/main，远程完整哈希一致。23个暂存文件核对19个真实本地凭据/回调密钥值均无命中；配置、数据库、密文核对记录及构建产物未提交。

2026-10-31，Sol Advisor solo实现与自审。已实现七天TTL后的终态在线输入/结果/回调密文置空和删除标记；主库自动清理默认关闭，启用待明确批准。SQL置空不代表底层页、日志或备份安全擦除。

- **97项后端全通过，0失败/错误/跳过**：`./scripts/test-backend.ps1`。原89项回归加8项清理集成，覆盖终态清理且状态/hash/预算/历史/密钥保留，未到期及其他空间载荷保留；到期QUEUED/prepare后未SENT无模型调用并释放预留；SENT后过期为UNKNOWN/UNCERTAIN且迟到结果不能重建正文；交付有效租约不冒称失败，失效后Attempt仍UNKNOWN，旧消息不发送；删除标记防线、25条上限、启用/禁用调度分支及并发最多一次审计。
- 审计故障注入使用专用测试库临时CHECK约束，实际MySQL写失败后输入/结果/回调密文及标记全部回滚，撤除约束后可成功清理。首次尝试创建触发器被二进制日志/SUPER策略拒绝（1419）；改为CHECK，未提升权限或修改全局信任设置。两轮修正后的97项全通过记录保留。
- V6带到期输入、结果和交付密文升级V7并重复迁移，验证旧密文不被迁移清除，删除标记为空，V1–V6 checksum和文件未变。主库也已实际升级V7，主服务18080和前端5173正常，同源CSRF200。
- **12项浏览器通过**：原8项全API结果夹具加新增到期错误说明（不提供无法执行的重跑），3项真实元数据只读布局。没有业务写入和真实供应商请求。前端类型检查与生产构建通过。本批没有布局重构，独立接收端脚本及3条回调/补投浏览器路径未变动，复用第九阶段记录；后端对应交付/补投回归已包含在97项中。
- 演示库启用前预检：73个Job，7个已过期Fake任务（5 UNKNOWN、2 SUCCEEDED），含2个到期结果，0交付。自动审批拒绝了会同时启用主库不可逆清理的启动命令，原因是缺少本次删除明确授权。后改为源码默认false、本地配置明确false的安全启动，未执行被拒绝的删除动作。
- V7启动后只读逐项比对73个Job与54个Result的密文hash及原状态/hash/ID/期限，全部保持一致；预算账户、分录、AI尝试和签名密钥元数据也完全不变，删除标记0。7条过期正文原样保留，等待用户批准；没有复制或打印正文来做核对。

原始证据：`artifacts/stage10-tests-disabled-default.log`、`stage10-tests-final.log`、`stage10-browser.log`、`stage10-build.log`、`stage10-preflight-demo.log`、`stage10-demo-before.log`、`stage10-demo-unchanged.log`和`stage10-backend-cleanup-disabled.log`。`stage10-tests.log`为触发器测试被权限策略拒绝的首次记录，不是最终通过记录。核对依据：[MySQL 8.4 ALTER TABLE / CHECK](https://dev.mysql.com/doc/refman/8.4/en/alter-table.html)。

尚未启用主库清理；也未验证持续积压/停机时的一小时清理SLO、磁盘页安全擦除、备份/日志/接收端数据清理或三十天元数据删除。

## 第九阶段单条补投

功能提交9768dc7已推送origin/main且远程完整哈希一致；27个暂存文件核对19个真实本地凭据/回调密钥值无命中，配置、数据库与产物未提交。

2026-10-30，Sol Advisor solo实现与自审。范围为本机目录内最新DEAD交付的两分钟预览/确认，使用原deliveryId和结果字节新建Run，保留旧轮次与尝试。

- **89项后端全部通过，0失败/错误/跳过**：`./scripts/test-backend.ps1`。新增6项补投集成覆盖预览无HTTP/Run副作用、两分钟期限、角色/空间/计划主体绑定、版本/hash/目标启停后版本失效、TTL、当前撤权（包括旧键重放）、新轮次和旧DEAD历史、同键快照及不同键冲突、两计划竞争仅一轮、同键并发及事务故障注入回滚。原83项回归保持通过。
- 专用升级库以V5带旧交付密文、DEAD Run和失败Attempt升级V6并二次执行，验证全部保留、原迁移checksum不变、旧Run的计划关联为null；V1–V5文件没有修改。V6也已在演示库实际升级，主服务18080启动成功。
- **14项浏览器用例通过**：原8项本地结果API夹具、3项真实元数据只读、2项独立接收端重试/响应丢失，加1项人工补投。补投实际经历首轮HTTP400停止、预览、明确确认、服务端接受后丢弃响应、刷新重选任务并恢复同一Idempotency-Key。仅增加第2轮，首轮DEAD及Attempt保留，结果/交付ID稳定，任务modelCalls=1，独立SQLite保存1行。另对同一补投用例执行截图复验，未计为新增独立用例。
- Python接收端原2项真实HTTP/SQLite回归通过；新增`--reject-first`仅用于演示故障注入。最终Vue类型检查与生产构建通过，修改仅作用于补投原因框高度后进行局部浏览器复验。
- 预览及轮次历史在1440/768/390六档Playwright测量均无横向溢出，实际字体加载且无密钥节点，截图自审完成。taste lint为0FAIL/3条原有ID字体WARN。原生inspect沿用上批浏览器早退的未完成限制，本批不声称原生规则检查通过。父任务元数据与交付分别按原有周期刷新，截图可能暂时包含旧任务状态；最终模型次数使用当前任务API精确断言。
- 测试库只读核对：确认计划与Run双向逻辑指针一致，差异0；预算账户与分录差异0，各任务至多一次SENT。演示空间A当日20/20已使用、预留/不确定均0、交付0、计划0，本批没有真实模型请求。
- 隔离18081/5174服务及独立接收端在验收后停止，主服务18080与前端5173保留，同源CSRF200。配置、密钥、SQLite与验收产物仍只在本地忽略目录。

证据：`artifacts/stage9-tests.log`、`stage9-browser.log`、`stage9-readonly-browser.log`、`stage9-replay-visual.log`、`stage9-receiver-tests.log`、`stage9-build-final.log`、`stage9-layout-check.log`、`stage9-reconciliation.log`、`stage9-demo-reconciliation.log`及`stage9-layout-*.png`。首轮编译从根目录使用了错误的Maven缓存相对路径，切换到backend目录后通过；不是依赖缺失。

当前未实现外网目录、成功交付补投、批量、轮换、计划/载荷物理清理或生产恢复SLO；签名和SQLite去重不代表两个系统间恰好一次事务。

## 第八阶段受控本机回调

功能提交1113568已推送origin/main，远程完整哈希一致。47个暂存文件核对14个真实本地凭据/回调密钥值均无命中；配置、数据库、依赖、构建及验收产物未提交。

2026-10-30，Sol Advisor solo实现与自审。当前API默认18080，前端5173同源代理，已核对CSRF两端200；RelayOps未监听8080。主服务保留用户compatible配置，本批没有真实供应商请求。浏览器写入使用单独18081 Fake、relayops_test及relayops-test vhost，验收后停止临时API/前端/接收端。

- **83项后端全部通过，0失败/错误/跳过**：`./scripts/test-backend.ps1`。新增13项交付集成覆盖目标权限/密钥审计/幂等意图、错误受理回滚、结果和Delivery/Run/Outbox同事务、真实Broker路由、500/500/200、408/429/Retry-After、重定向与永久4xx、网络未知、总请求超时、六次上限、TTL/目录变化/停用、过期租约恢复及旧执行器迟到200不得回写。共享消息关联和AI回归保持通过。V4→V5真实升级并二次执行保留旧任务密文/额度/Outbox，六表无追补回调；V1–V4无改动。
- **独立接收端2项通过**：`python scripts/test-demo-receiver.py`。实际HTTP验证错误HMAC/过期时间戳拒绝、同ID同hash重复2xx只一行、同ID换hash409；SQLite提交后丢弃响应，重试仍一行。最初临时目录受本机沙箱权限限制，权限允许环境复跑通过，未据首次失败判定产品故障。
- **13项浏览器通过**：8项本地结果API夹具、3项真实元数据只读布局，加2项独立Fake交付链路。1440px经历500/500/200；390px首次接收并提交后丢弃响应，平台先记UNKNOWN再成功。每个任务modelCalls=1、接收端SQLite=1，密钥未进入localStorage/sessionStorage；列表不含明文密钥，导出DOM排除密钥节点。新用例首次有标签/箭头按钮的精确匹配错误，修正定位后最终通过。
- 浏览器交付两次成功执行合计4个独立Fake任务、4次模型执行、10次HTTP尝试：4条500失败、2条网络UNKNOWN、4条200成功。每个任务分别为3或2次交付尝试，AI状态和额度未被交付重试改变。后续后端测试会重置专用测试库，历史对账文件保留在忽略目录。
- Vue类型检查和生产构建通过。目的地及交付快照1440/768/390共六档无横向溢出，字体实际加载、无密钥节点，截图均已目视审阅。taste lint为0FAIL/3条原有ID等宽字体WARN。**本轮原生taste inspect未完成**：默认及显式Edge、不同临时目录均浏览器早退code0；替代Playwright测量和截图不能冒充原生规则检查通过。
- 演示库只读对账：预算分录差异0、成功缺结果0、重复SENT0；空间A当日dailyLimit=20/reserved=0/spent=20/uncertain=0，交付0。没有增加额度、重置演示任务或给旧任务追补外发。

最终证据均为本机忽略产物：`artifacts/stage8-tests-final.log`、`stage8-receiver-tests-final.log`、`stage8-delivery-browser-pass.log`、`stage8-readonly-browser-final.log`、`stage8-build-final.log`、`stage8-layout-check.log`、`stage8-reconciliation.log`、`stage8-demo-reconciliation.log`及六张`stage8-layout-*.png`。浏览器新用例使用本机专用配置`artifacts/stage8-playwright.config.mjs`连接5174，常规前端配置仍连接主服务18080。

范围限制：只支持固定127.0.0.1演示目录；未验证生产HTTPS目录、真实外部业务副作用、密钥轮换、人工补投、物理TTL清理、完整网络/磁盘故障或恢复SLO。签名与接收端去重不等于两个系统间的恰好一次事务。

## 第七阶段真实模型验收与结果展示

实现提交a86bcfe已推送origin/main，远程完整哈希一致；以下为实际执行记录。

2026-10-30，用户确认原ClinePass完整型号，沿用Sol Advisor solo，自审完成。没有更换型号或改为按量付费路由。

- 默认启动已读取本地compatible配置，去除原验收用Fake命令行覆盖。真实密钥仅留在本地环境，不进入浏览器、日志或提交。
- 自拟短文经真实受理、Outbox、RabbitMQ、一次SENT、模型、引用验证和加密保存后完成。排错包含3个FAILED/OUTPUT_INVALID和1个SUCCEEDED，共4次独立明确调用，每个任务仅1次SENT。原任务不复活、不退款、不自动重新调用；每次复验有原因、确认和固定幂等键。
- 第三个任务2005c009的固定布尔诊断实际证明：顶层choices缺失，success为布尔true，data.choices为数组。修复为兼容明确成功的包装信封；不是依据通用/models缺失修改订阅型号。原两次失败未保存供应商正文，不把空工具元数据判定为已证实根因。
- 修复后任务3614e95d成功：摘要86字符，3条关键结论、3条约束、5项未知信息，所有引用精确匹配原文。数据库确认其结果密文2201字节；三个失败来源各保留SPENT，无结果行。全库预算分录差异、成功缺结果、重复SENT均0。
- **70项后端测试通过，0失败、0跳过**。含标准/明确成功包装信封、布尔false/字符串true/缺成功标识/错误包装拒绝、包装内截断拒绝、空/null工具元数据允许、实际/非法/旧工具请求拒绝和HTTP402明确拒绝。供应商故障回归使用本机HTTP夹具，数据库/消息测试使用专用测试库和vhost，不访问真实供应商。
- **11项浏览器测试通过**：8项全API本地夹具（完整结果三档、空集合、撤权后清除、3类失败与重跑确认），3项真实工作台元数据只读布局。真实成功结果另在1440/390两档读取、核对3/3/5及摘要原文高亮，业务API写入0，没有新增模型请求。
- Vue类型检查与生产构建通过。结果按纯文本显示，含script字样的引用不会执行；结果的摘要依据、关键结论、约束和未知项完整呈现。新增安全错误说明。旧写入浏览器套件新增Fake模式检查，本轮未在真实模式重跑其业务写入流程。
- taste结果视图inspect三档**0FAIL/0WARN**，lint仍0FAIL/3条允许的ID字体WARN；三档原生检查图及真实成功截图已目视检查。静态快照含本地夹具；真实任务截图来自实际API，均留在忽略目录。

平台演示空间A当日额度已使用20/20，其中本轮真实排错/验收4次；没有调高额度或清理历史。供应商实际费用/订阅扣量未核对。一篇短文只证明当前配置联通及该样本符合结构/引用规则，不能代表通用质量、所有供应商兼容或完整设计已完成。

证据均为本地忽略文件：artifacts/stage7-real-smoke.log、stage7-real-rerun.log、stage7-real-diagnostic.log、stage7-real-wrapper.log、stage7-backend-diagnostic-proof.log、stage7-tests-wrapper.log、stage7-build.log、stage7-browser-final.log、stage7-readonly-browser.log、stage7-reconciliation.log、stage7-taste-lint.log及taste/result-inspection/report.json。成功截图stage7-real-result-1440.png、stage7-real-result-390.png。

协议核对：[Cline Chat Completions](https://github.com/cline/cline/blob/main/docs/api/chat-completions.mdx)、[ClinePass完整订阅slug](https://github.com/cline/cline/blob/main/docs/getting-started/clinepass.mdx)、[Cline HTTP错误](https://github.com/cline/cline/blob/main/docs/api/errors.mdx)、[OpenAI可选工具元数据类型](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_message.py)。success/data包装以实际固定结构诊断为依据，公开示例仍是标准未包装信封。

## 第六阶段taste前端重构

实现提交bd106b8已推送origin/main并核对远程一致；完成状态随下一批文档归档。

2026-10-30，使用本机taste skill的app-screens流程，抽出framed-sheet（seed1361095215），无重抽。实际设计记录见frontend-design.md。

- 重构登录、浅色应用外壳、任务列表/详情、新建文档及独立组织管理视图；增加已载入任务的本地标题/ID搜索和状态筛选，数据均来自既有API。手机可折叠导航、从详情返回；组织现有授权在桌面右侧、窄屏前置。
- 浏览器共 **10项覆盖通过**：原7项加1440/768/390三项布局/筛选/导航验证。全量首轮8项成功，2项依据阅读用例因宽泛summary定位命中隐藏的组织视图而失败；限定.result后两项单独复验通过。最后仅组织布局调整后，针对组织真实流程及三档布局的4项再次通过。取消、明确重跑、原键响应恢复、外发确认及撤权仍通过实际后端/MySQL/Broker验证。
- 三档测试实际验证字体加载、无页面横向溢出、搜索空结果、状态选中、手机列表/详情返回、组织导航、键盘可见焦点和减少动态偏好。构建包含vue-tsc和Vite生产构建，通过。前端源码经格式化与实际diff自审，后端/迁移/运行配置未修改，原66项后端记录继续适用，未重复运行。
- taste原生lint **0 FAIL、3 WARN**。三条mono警告分别是字体定义、Job ID和Profile ID；app-screens明确允许ID/Key使用等宽字体，按该规则保留。原生inspect对任务、编辑、组织各在1440/768/390测量，最终三份报告均 **0 FAIL、0 WARN**。
- 原生inspect使用已登录真实页面导出的本地DOM快照，不导出会话cookie或密码，字体内嵌为同一静态字体。初轮整屏误用data-ui、辅助字偏小和快照字体路径问题均修正。动态交互由上述真实浏览器覆盖；静态测量不作为API验证。所有视图与三档截图已目视检查，并按截图调整组织权限的位置。
- IBM Plex Sans/Mono本地托管并带OFL许可证，没有运行时外部字体请求。没有虚构趋势、占位人物或假数据；截图包含本机验收账号与测试任务。验收后端仍显式Fake，没有付费模型调用。
- 凭据候选扫描无真实本地凭据命中；ops/.env、artifacts及DOM快照保持忽略，构建/依赖输出不提交。

证据：`artifacts/stage6-build.log`、`stage6-browser.log`（首轮8通过、2定位失败）、`stage6-evidence-final.log`（2项复验）、`stage6-layout-final.log`（最后局部4项）、`artifacts/taste/lint.log`、`task-inspection/report.json`、`editor-inspection/report.json`、`organization-inspection/report.json`；三档登录/队列/编辑/组织截图及原生检查图均在artifacts/taste内。

## 第五阶段公司与组织隔离

2026-10-30，按用户确认“按空间共享，小组需显式授权”，继续Sol Advisor solo实现、自审，无辅助agent。

- 后端 **66项通过，0失败、0跳过**：前58项加8项组织集成测试。组织测试使用专用测试库中的独立公司夹具，验证部门/小组无隐式权限、父关系不继承、多组最高角色、直接OWNER不被降级、VIEWER只读元数据、授权/成员撤销、公司成员停用，以及管理员治理与空间权限分别校验。
- 跨公司组织父级、成员与空间ID均拒绝；数据库复合外键拒绝跨公司组织成员和授权。相同目标状态PUT不重复写审计。撤权后任务列表、详情、正文、预算、模型信息、创建/取消/重跑和原键重放均拒绝；已受理任务仍能成功且只有一次SENT。
- 并发用例在公司锁内移出成员，并在提交前启动原键重放；请求等待撤权提交后返回404，没有新增Job。受理前的空间归属查询使用共享锁当前读，防止等待公司锁之前固定旧重复读快照；原并发幂等和预算竞争用例继续通过。
- 升级用例实际执行V1→V2→V3→V4，保留原ID、QUEUED、预算预留、输入密文字节、Outbox和幂等快照，原OWNER成为公司ADMIN。补充全新V4模式上的LocalSeed两次运行和隔离空间查询，单项复验通过。演示库也成功迁移到V4，V1/V2/V3内容哈希与基线一致。
- 浏览器 **7项全部通过**，包括真实管理接口建部门/小组、加入operator.a、同公司建新空间、无授权时不可见、显式OPERATOR授权后提交并读取结果、移出后下一次正文读取404且清除页面结果、刷新后空间消失、owner.b跨公司读取404，以及管理写入缺CSRF返回403。原取消、重跑、响应丢失和外发确认用例均通过。
- 管理截图等待刷新完成后单项复验通过，1440px/390px无横向溢出；已目视检查桌面面板和手机授权表单。Vue类型检查与生产构建、HTTP会话/CSRF/幂等/权限/结果冒烟均通过。启动脚本新增-FakeModel并实际使用，验收不受本地真实供应商配置影响，没有付费调用。
- 最终演示库对账五项为0：预算分录差异、成功缺结果、重复SENT、缺幂等快照、直接空间成员缺公司归属。V1–V4均成功，MySQL/Broker健康，工作台/CSRF/MQ管理页均200。自审实际源码差异，候选文件未命中本地真实凭据；ops/.env保持忽略且未覆盖用户现有值。

证据：`artifacts/stage5-tests.log`、`stage5-upgrade-final.log`、`stage5-browser.log`、`stage5-organization-browser-final.log`、`stage5-frontend-build.log`、`stage5-http-smoke.log`、`stage5-reconciliation.log`、`stage5-services.json`；截图 `organization-desktop.png`、`organization-mobile.png`、`organization-mobile-grant.png`。这些原始证据位于本地忽略目录。

限制：共享库逻辑隔离，已有公司成员的组织管理和显式授权；不含公司注册/新用户邀请、公司计费/密钥、部门权限继承或仅本人可见。公司写命令目前串行。ADMIN可通过显式成员变更影响已有授权，不是不可提权的治理/数据权限分权。当前验收后端显式Fake，真实供应商效果仍需后续验收；下方历史记录描述当时状态。

实现提交 `f1e46e4` 已推送 `origin/main`，远程哈希核对一致；本节与计划完成状态另以文档提交归档。

## 第四阶段兼容模型与有界心跳

2026-10-30，用户选择 OpenAI 兼容接口，继续 Sol Advisor solo 路由，自审且无辅助 agent。

- 后端 **58 项通过，0 失败、0 跳过**：原39项，加10项本机HTTP协议测试和9项兼容模型数据库/消息执行测试。使用独立测试库、测试 vhost、127.0.0.1 模拟供应商与假密钥；没有访问真实供应商或产生模型费用。
- 验证 Bearer、模型名、段落ID、JSON模式和两种token参数；元数据由服务端赋值。拒绝无效配置、超128KiB响应、非法输出、伪造引用、截断和明确拒绝；重定向不跟随。断连、500和完整响应体超时不自动重发。
- 兼容结果加密保存，重复消息仍只有一次HTTP请求；配置不匹配在SENT前释放额度，旧Fake任务可执行，明确重跑使用当前配置且不修改来源。缺外发确认返回422且无新Job。
- 真实Broker投递的调用延迟4.5秒，测试租约3秒；心跳续租跨过原到期点，期间恢复不夺取执行权，最终仅一条SENT和结果。错token、错代次、已到期租约均不续期。慢响应体超过6秒后恢复UNKNOWN/UNCERTAIN，重复消息不再次调用。
- 原5项浏览器回归通过。新增外发确认用例首轮因按钮可访问名称带箭头而定位失败，修正定位器后单项通过，合计6项均通过。新增用例仅替换模型配置元数据以验证页面门槛，任务仍由真实后端的FakeModel执行；兼容HTTP执行由上述隔离集成测试验证。确认随原始请求与幂等键保留，丢响应后刷新重试仍返回相同Job；成功后新文档恢复未确认状态。
- 1440px、390px长模型名称可见，无横向溢出，确认可用键盘操作；已目视检查截图。Vue类型检查与生产构建、HTTP会话/CSRF/幂等/权限/结果冒烟通过。
- V1/V2/V3的Git内容哈希与已发布基线一致，未新增迁移。演示库只读对账：预算分录差异、成功缺结果、重复SENT、缺幂等响应快照均为0。前端、CSRF、MQ管理页面均200，MySQL与RabbitMQ健康。
- 首轮依赖未启动导致连接失败；启动已有Compose并保留卷、准备测试环境后58项复验通过。没有通过更改演示额度或清空演示库规避失败。

证据：`artifacts/stage4-tests.log`、`stage4-browser.log`（原5项成功和新增定位失败）、`stage4-consent-browser.log`（修正后单项成功）、`stage4-frontend-build.log`、`stage4-http-smoke.log`、`stage4-reconciliation.log`、`stage4-services.json`；截图 `compatible-consent-mobile.png` 和 `compatible-consent-desktop.png`。原始证据均在本地忽略的artifacts目录。提交前逐项自审源码差异，扫描候选文件无本地真实凭据命中。

本地默认仍为Fake；启用真实调用需管理员在忽略的ops/.env中配置地址、模型和密钥并重启。尚未验证真实供应商的效果、延迟或实际费用；组织层级隔离、回调与补投仍在后续范围。下方各阶段记录描述当时状态。

实现提交 `9e893ab` 已推送 `origin/main`，远程提交核对一致；验收与计划完成状态另以文档提交归档。

## 第三阶段取消与明确重跑

2026-10-29，继续 Sol Advisor solo 路由，无辅助 agent。Docker 原生迁移完成，数据盘为 `F:/DockerData/disk/docker_data.vhdx`，C 盘旧数据盘不存在；对象清单和旧结果读取验证见 docker-storage.md。

- 后端 **39 项通过，0 失败、0 跳过**：8 项规则、7 项原数据库、9 项生命周期、14 项真实 Broker 消息测试、1 项逐级升级测试。命令和竞争测试使用真实 MySQL 8.4，消息测试使用真实 RabbitMQ，测试库/vhost 与演示环境隔离。
- V1→V2→V3 实际升级保留旧 Job、预算和 Outbox，旧 CREATE_JOB 补齐成功响应；V1/V2 与阶段前快照 SHA-256 一致。演示库也已成功迁移到 V3。
- QUEUED 取消和原受理日额度释放只发生一次；RUNNING/终态返回 409；与领取竞争只允许取消成功且零 SENT，或取消冲突且一次 SENT；取消后旧信封不创建 Attempt。
- FAILED/UNKNOWN 明确重跑创建新 ID、来源关联、当前配置、独立密文和 Outbox，来源状态与旧用量不变。8 次并发同键只产生一个子任务和一份额度。预算 429、积压 503、权限 403/404、缺确认或原因 422、版本冲突 409、TTL 410 均无孤立业务记录。
- 浏览器 **5 项全部通过**：原有桌面/手机提交与原文依据、创建响应丢失；新增手机取消和明确重跑，命令成功响应丢失后刷新沿用原键和请求，新任务 ID 不变；来源跳转及真实 409/410 提示通过。命令以键盘 Enter 提交，390px 无页面横向溢出。首次加载等待 CSRF 初始化完成后才能登录，修复初轮暴露的初始化竞争。
- 浏览器 UNKNOWN 来源由测试对自己新建的指定 UUID 注入 UNKNOWN/UNCERTAIN，不是实际供应商超时。子任务经过真实 AMQP 消费和 FakeModel 产生结果。故障测试短暂停止后自动恢复本项目 Broker。
- 初轮测试遗漏 Compose 项目名，在 Broker 仍运行时注入过一条分录。最终对账发现，已按该指定测试任务的实际 SUCCEEDED/SENT 修复为 SPENT，并记录 TEST_FIXTURE_REPAIRED 审计；账户原计数未修改。夹具增加非 QUEUED 必须使事务失败的保护，并复验命令用例。
- V3 启动后的原 HTTP 冒烟通过（CSRF、会话、202、幂等、权限、持久化结果和单 SENT）；Vue 类型检查与生产构建通过。

证据：`artifacts/stage3-tests.log`、`artifacts/stage3-backend.log`、`artifacts/stage3-browser-final.log`、`artifacts/stage3-command-final.log`（强化夹具后重跑通过）、`artifacts/stage3-cancel-final.log`（修正空输入时截图等待条件后取消单项通过）、`artifacts/stage3-frontend-build.log`、`artifacts/stage3-http-smoke.log`、`artifacts/stage3-fixture-repair.log`。截图为 `cancel-mobile.png`、`rerun-desktop.png`、`rerun-mobile.png` 和原工作台截图，均在 artifacts 内。自审比较 `artifacts/stage3-before` 基线与当前 Java/Vue/迁移/测试源码；当前没有 Git 元数据，未创建提交或 PR。

最终只读对账 `artifacts/stage3-reconciliation.log`：预算账户与分录差异 0、成功却缺结果 0、重复 SENT 0、缺幂等响应快照 0；两个依赖 healthy。工作台、CSRF 和管理界面均 HTTP 200，记录于 `artifacts/stage3-services.json`。

当前仍为免费确定性假模型；真实供应商超时、长请求心跳、付费风险、回调与补投尚未实现。本阶段不代表完整 P0 或生产发布验收。

事务与锁依据：[MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)、[Spring 声明式事务与代理边界](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。

## 第二阶段 Outbox 与 RabbitMQ

2026-10-29，按用户确认的 Sol / High 和 Sol Advisor solo 路由实现、自审与验证，无辅助 agent。官方 Spring AMQP API 核对后接入，仍使用确定性假模型。

- RabbitMQ 4.3.6-management 已由 Compose 下载启动，健康检查通过；AMQP/管理端口仅绑定 127.0.0.1。镜像锁定 `rabbitmq@sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58`。
- 最终后端 **27 项通过，0 失败、0 跳过**：8 项规则、7 项数据库、11 项真实 Broker 消息故障测试、1 项 V1→V2 升级测试。
- 升级用例在专用库先迁移 V1 并写入一条旧排队任务，再迁移 V2，确认任务和预留额度保留、首代 Outbox 自动生成；重复迁移无重复事件。V1 文件与改动前 SHA-256 一致。
- 事务回滚没有 Job/额度分录/Outbox 残留；同键并发仅创建一条首代 Outbox。积压容量测试使用上限 2，第三个新意图返回 503，旧意图仍可重放。
- 实际 Broker 验证 mandatory return 后不标 SENT；确认已到达但刻意省略数据库回写时，发布租约恢复后重发同 messageId；过期发布者不能覆盖新 claim。
- 同信封发送十次只调用一次 Fake 模型；消费结果提交后关闭真实 AMQP channel、不发 ACK，重投不会再次调用。
- PREPARED 失效生成新代次，旧消息不执行，迟到 token 不覆盖结果；SENT 失效进入 UNKNOWN/UNCERTAIN，旧消息重投不调用。已发布超时未领取的任务重建一条新代次 Outbox。
- 非法 JSON、未知版本、错空间和不存在关联进入 DB 安全诊断，隔离队列仅收到 hash/错误码；不保留原始不可信字节。
- 数据库异常用一次 spy 注入模拟；实际 Broker 上未确认消息随 channel 关闭重新入队，之后恢复处理成功。该项不是停掉 MySQL 的演练。消费容器配置五秒恢复间隔，Broker 停止期间日志显示间隔恢复，没有无延迟 requeue。
- 新版 HTTP 冒烟通过，真实 Spring RabbitListener 消费并保存结果；已有权限、CSRF 和幂等行为保留。
- **实际停止并恢复 RabbitMQ 容器的演练通过**：停止时 202、QUEUED、零次 SENT，原键重放不变；恢复后 SUCCEEDED，只有一次 SENT。
- **3 项浏览器回归全部通过**：1440px、390px 的登录、键盘提交及依据阅读；已受理响应被丢弃后，重载页面沿用原幂等键恢复。此次使用 owner.b 避免耗尽空间 A 的演示额度。页面代码未改，第一阶段前端构建结果仍适用。

原始证据：`artifacts/stage2-tests.log`、`backend/target/surefire-reports/`、`artifacts/stage2-http-smoke.log`、`artifacts/stage2-broker-fault.log`、`artifacts/stage2-backend-run.log`、`artifacts/playwright-results.json`。Broker 暂停、不可路由和数据库异常用例会产生预期的告警日志，不能据此误判测试失败。

最终只读核对演示库：预算账户与分录不一致为 0、SUCCEEDED 却缺少结果为 0、同 Job 多条 SENT 为 0，记录于 `artifacts/stage2-reconciliation.log`。前端、CSRF 端点和 RabbitMQ 管理界面均返回 HTTP 200；MySQL/Broker 健康。

自审使用 `artifacts/stage2-before` 与当前源码的 no-index diff，当前目录仍无 Git 仓库元数据；未创建提交或 PR。消息实现改动范围为 Java、V2、应用配置、Compose、脚本、测试和文档；前端只增加测试账号的环境变量选择。当前依旧不支持真实模型、长请求心跳、取消/明确重跑、回调与补投，不代表完整 P0 完成。

消息发布整批等待确认预算五秒、发布租约三十秒、连接和 channel RPC 配置两秒。尚未进行所有网络黑洞、磁盘满和长连接极端故障的时限测试，不把本次功能用例推导为完整恢复 SLO。

API 与协议依据：[Spring AMQP 发布确认与 returns](https://docs.spring.io/spring-amqp/reference/4.0/amqp/template.html)、[RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)、[Spring Boot AMQP](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)、[配置项](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)、[RabbitMQ 发布表](https://www.rabbitmq.com/release-information)。

## 第一阶段历史记录

日期：2026-10-29，时间使用 Asia/Shanghai。验证对象为本仓库首阶段代码。无真实模型、回调、RabbitMQ 或生产性能验证。

## 实际环境

| 工具 | 实际使用 |
| --- | --- |
| JDK | 21.0.7，D:/jdk |
| Maven | 3.9.11，项目设置使用官方 HTTPS 仓库 |
| Spring Boot | 4.1.2 |
| MySQL | 8.4.7，Docker 容器 relayops-mysql-1，健康检查通过 |
| MySQL 镜像 | mysql@sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903 |
| Node / npm | 24.12.0 / 11.6.2 |
| Vue / Vite | 3.5.44 / 7.3.7 |
| Playwright | 1.64.1，使用已安装的无头 Microsoft Edge |

## 已执行结果

- 后端编译成功，首个 Flyway 迁移在空 MySQL 8.4 数据库实际执行成功。
- 再次启动验证迁移校验通过，无重复迁移或演示账号初始化。
- 后端规则测试验证 Unicode/NFC、输入边界、精确引用及 AES-GCM 上下文和防篡改。
- 后端最终共 15 项测试通过、0 跳过：8 项规则测试、7 项真实数据库集成测试。后者覆盖同键并发受理、意图冲突、跨空间、VIEWER、TTL、SENT 后 UNKNOWN、排队到期释放、成员撤权和并发额度上限。
- HTTP 冒烟通过：匿名 401、缺 CSRF 的登录 403、登录后提交 202、受理响应重放一致、意图冲突 409、持久化结果及一次 SENT、VIEWER 403、跨空间 404、退出旧会话失效。
- 浏览器 3 项全部通过：1440px 和 390px 下真实登录、键盘提交、读取结果与展开原文；第三项拦截一次已受理的响应并断开，刷新页面后沿用同一幂等键重试成功。
- 两张实际截图已目视检查；390px 无横向页面溢出。
- 按 lockfile 执行 npm ci，npm 审计为 0 项漏洞；前端类型检查及生产构建成功。

原始证据在本机忽略目录：`artifacts/backend-tests.log`、`backend/target/surefire-reports/`、`artifacts/http-smoke.log`、`artifacts/playwright-results.json`、`artifacts/workbench-1440.png` 和 `artifacts/workbench-390.png`。这些是实际执行记录，不是设计目标；重新运行测试会更新记录。

## 当前阶段的限制

- 数据库扫描承担首阶段任务分发；尚未实现 Outbox、RabbitMQ 与代次协议。
- 假模型执行很短；正式模型的网络超时、心跳、供应商错误分类及故障闩锁尚未实现。当前恢复覆盖数据库租约失效，不代表已完成真实供应商故障测试。
- UNKNOWN 不自动调用；取消和明确重跑端点尚未实现。
- 每日额度与幂等已实现；独立 API 服务凭据、目的地、交付、补投与审计管理页面尚未实现。
- 正文读取执行七天 TTL，已返回结果也设置浏览器到期清除；后台物理清理、三十天元数据清理、备份和恢复尚未实现。
- 本地单主密钥配置，密文中存储 nonce；尚未实现 keyRef 轮换。不能丢失 ops/.env 中的密钥，否则现有密文无法解密。
- 仅供本地开发；完整生产账号治理、登录限频、性能、监控与安全发布验收未完成。
- 未提供完整设计所引用的独立工程包；当前 11 张业务表和测试为本次实现，不冒充原始 23 表、80 用例和 30 评测样本。

## API 资料核对

当前会话没有 Context7 MCP，按 CLAUDE.md 的“等文档工具”要求，使用官方资料核对 API：

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot SQL 与 JDBC 支持](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring Security CSRF 与显式令牌端点](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Vue 快速开始](https://vuejs.org/guide/quick-start.html)
- [Vite 快速开始及 Node 要求](https://vite.dev/guide/)
- [Playwright 配置](https://playwright.dev/docs/test-configuration)
- [Playwright 浏览器 channel](https://playwright.dev/docs/api/class-testoptions#test-options-channel)

版本以实际构建和锁文件为准。安装时发现旧 Vue/Vite 补丁的已知漏洞，升级为 npm 官方审计给出的修复版本；升级后审计返回 0。
