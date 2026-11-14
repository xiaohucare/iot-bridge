# iotbridge 设计评审报告

> 评审对象：`com.iotbridge:iotbridge`（9 个 Maven 模块，约 9.1k 行 Java，81 个单元用例）
> 评审范围：架构分层、可靠性语义、安全、可观测性、工程化、文档
> 评审日期：2026-11-14
> 说明：本文按 **P0（上线阻塞）/ P1（必须尽快补）/ P2（持续改进）** 分级，每条给出「证据 → 影响 → 建议」。行号基于当前工作区代码。

---

## 0. 总体结论

**做得好的部分（应保留）**

- 分层意识清晰：`common / spi / plugin-* / mq / access-* / bridge-core / admin / starter` 自底向上，`ProtocolCodec` SPI + `ServiceLoader` 让新增协议不用改主干。
- 会话寻址用 `SessionKey(protocolType, deviceId)` 复合键，真正解决了「多协议同号互不覆盖」这个 IoT 网关最常见的坑，并且贯穿了注册、下行、缓冲、补发、查询五条链路。
- 很多「容易被忽略的正确性细节」写对了：Kafka `acks=all + 幂等`、写缓冲水位 + `isWritable` 反压、补发存原始指令而非字节帧（规避 `timeCalib` 时间戳失效）、补发失败退回剩余帧、`register` 时踢旧连接、启动期协议/端口 fail-fast 校验、REST 错误码到 HTTP 状态的分级映射。
- 注释与 Javadoc 质量高于多数同类项目，`CONFIG` 项的边界语义（`<=0` 的含义）都写清了。

**核心判断**

代码的「单机、可信设备、单实例」路径是可靠的；但**一旦按生产口径审视（设备不可信、多实例、要 SLA、要被运维）**，还缺四块地基：**设备身份与传输安全**、**端到端可观测性**、**跨实例的状态归属**、**无效/失败路径的兜底**。文档层面则是「有手册、缺契约与决策」——README 承担了过多职责，真正需要长期维护的架构、API 契约、安全模型、运维 runbook 都还没有。

各模块成熟度粗评：

| 模块 | 成熟度 | 主要短板 |
| --- | --- | --- |
| `spi` | 中 | 契约语义不完整（异常、幂等、序号、应答），只有「编解码」没有「会话/应答」模型 |
| `bridge-core` | 中上 | 反向依赖具体传输层；补发任务模型有放大风险；无指标 |
| `access-netty` | 中上 | 无连接数上限、无鉴权、停机关闭不排空 |
| `access-mqtt` | 中 | 全局共享口令、默认开启、QoS1 无确认跟踪、无 Will/retained |
| `mq` | 中 | 上行失败只打日志；契约放在 `mq.api` 而非 `spi` |
| `admin` | 中 | 无分页、无审计、同步阻塞等 confirm、无 OpenAPI |
| `plugin-sos` | 良 | 魔法下标常量、下行 `seq` 恒 0、无真实样例帧基准 |
| `plugin-demo` | 良 | 被 core 以 `runtime` 依赖带上生产包 |
| `starter` | 中 | 无 actuator/logback/Dockerfile/CI |

---

## 1. P0 — 上线阻塞

### P0-1 设备侧零身份认证，且可被「冒名顶替」

**证据**
- TCP 接入（`:9000/:9001`）没有任何鉴权：`DeviceInboundHandler` 把帧直接交给 `UplinkDispatcher`，`deviceId` 来自协议解码结果（SOS 的 IMEI，`SosProtocolCodec#resolveDeviceId`）。
- `ChannelManager.register`（`iotbridge-access-netty/.../ChannelManager.java:79`）在 `(protocolType, deviceId)` 重复上线时**主动 close 旧连接**。

**影响**
任何能连到端口的客户端，只要伪造一个已知 IMEI，就能：(1) 把真实设备踢下线；(2) 让网关把本该发给真实设备的下行指令**发到攻击者连接上**（下发类指令如远程配置、重启、参数写入通常是高危操作）；(3) 向 Kafka 注入伪造上行，污染业务数据与告警。

**建议**
1. 引入设备注册中心（`deviceId → productKey/secret/protocol/能力集`），接入层先解析/校验身份再绑定会话；至少提供 `device-secret`（HMAC over 首帧 nonce）或预共享密钥的接入认证。
2. 在认证落地前，把「踢旧连接」改为可配置策略（`reject-new` / `kick-old`），默认拒绝新连接并告警——避免冒名顶替直接接管会话。
3. 设备白名单（`deviceId` 不在册即拒连并计数），作为最小可用的兜底。

### P0-2 全链路明文 + 安全默认值是「关」

**证据**
- TCP 9000/9001 明文；MQTT 1883 明文；REST 未开 `server.ssl.*`。
- `iotbridge.admin.api-key` 缺省为空 → `ApiKeyAuthFilter.shouldNotFilter` 直接放行（仅打一条 WARN，`AdminConfig.java:31`）。
- `MqttServer` 的 `@ConditionalOnProperty(..., matchIfMissing = true)` 意味着**默认就对外开 1883**；`iotbridge.mqtt.username` 为空时允许匿名。
- MQTT 口令是**全局单一账号**（`MqttConnectionHandler#authenticate`），无法按设备吊销。

**影响**：任何部署到公网的实例，默认是一个「开放的下行指令执行入口」。合规审计（等保/客户安全评估）基本过不了。

**建议**
1. 安全默认值反转：MQTT 接入 `enabled` 默认 `false`；`prod` profile 下 `api-key` 为空、`mqtt.username` 为空直接**启动失败**（fail-fast 而不是 WARN）。
2. TLS：MQTT 走 8883（Netty `SslContext` + `SslHandler`）；TCP 二进制协议如设备不支持 TLS，至少在架构上明确「网关前置 TLS 终结/专网」并在部署文档画拓扑。
3. MQTT 改为「用户名/密码 → 设备身份」映射（或每人一密），支持按设备吊销；补 rate limit 与 CONNECT 频率限制（当前只有总连接数上限）。

### P0-3 下行「已送达」语义不准，会造成重复下发

**证据**：`ChannelManager.sendToDevice`（`ChannelManager.java:186-193`）对 `writeAndFlush` 做 `future.await(5000ms)`，**超时即返回 `false`**。但 Netty 的写操作超时**不代表数据没写出去**——可能只是 flush 完成得慢。返回 `false` 后 `DownlinkDispatcher` 会把指令存入 `DownlinkBuffer`，设备下次上线再补发一次。

**影响**：弱网/慢设备下，同一条「重启/参数写入」指令可能被设备执行两次。对消防类设备属于不可接受的行为偏差。

**建议**
1. 区分三态：`DELIVERED / UNKNOWN / NOT_DELIVERED`。只有 `NOT_DELIVERED`（`channel==null`、`!isActive()`、`!isWritable()` 前置检查失败）才进缓冲；`UNKNOWN`（await 超时）走「不重投 + 计数告警」或带 `requestId` 的去重补发。
2. 下行指令携带幂等键（`requestId` 已被模型承载但没被使用），设备侧/网关侧按 `(deviceId, requestId)` 去重。
3. 真正的闭环是「设备应答」，见 P1-5。

### P0-4 TCP 接入没有任何资源上限，可被低成本打爆

**证据**：`NettyTcpServer` 只有 `SO_BACKLOG=1024`、`READER_IDLE`、写水位；**没有最大连接数、没有 per-IP 限制、没有接入速率限制**（对比 MQTT 侧有 `maxConnections`）。同时 `ChannelManager.deviceChannels` / `deviceIndex` / `DownlinkBuffer.deviceOrder` 都以外部可控的 `deviceId` 为键。

**影响**：单机几十万空连接（或大量随机 IMEI 的伪造帧）即可耗尽文件句柄与堆内存；`deviceIndex` 里 `deviceId` 无上限增长。

**建议**
1. 增加服务端级 `maxConnections` + `ChannelGroup` 计数（与 MQTT 侧对齐），超限拒绝并计数。
2. `deviceId` 长度/字符集校验（当前几乎无约束）+ 未认证设备数量上限。
3. per-IP/per-device 帧速率限制（令牌桶），慢速攻击用 `connectTimeout`（TCP 侧目前只有读空闲，没有「建连后必须尽快发首帧」的超时）。

---

## 2. P1 — 必须尽快补齐

### P1-1 补发任务模型存在放大风险

**证据**
- `UplinkDispatcher.process` 每处理一帧就调 `downlinkReflowService.reflow(...)`（`UplinkDispatcher.java:170`），而 `reflow` 每次都向线程池提交一个任务。
- `newReflowExecutor` 用 `Executors.newFixedThreadPool`（`DownlinkReflowService.java:102-108`）→ **无界 `LinkedBlockingQueue`**。

**影响**：开启 `buffer-enabled` 后，稳定心跳（如 60s/设备 × 10 万设备）也会持续产生任务；设备集中重连时任务堆积速度远超消费速度，队列无界增长 → 内存压力与延迟雪崩。

**建议**
- 入口处先判「该会话是否有待发数据」（`pendingSize(key)>0`）再提交。
- 线程池换 `ThreadPoolExecutor` + 有界队列 + `CallerRuns`/丢弃+计数；或改成「上线事件驱动 + 每设备去重标记（`Set<SessionKey> inFlight`）」。

### P1-2 上行失败即丢，且不可观测

**证据**：`KafkaUplinkProducer.send`（`KafkaUplinkProducer.java:45-52`）异步发送，失败只 `log.error`。`UplinkDispatcher` 队列满时 `AbortPolicy` 丢弃，`droppedFrames` 只是类内 `AtomicLong`。

**影响**：broker 抖动/主题限流期间上行静默丢失，业务侧无感知；无法回答「今天丢了多少帧、哪个设备丢的」。

**建议**
1. 分级兜底：`local retry buffer`（有界、按 deviceId）→ 失败计数 → 指标；关键设备可落本地磁盘队列。
2. 把 `droppedFrames`、`sendFailures`、`queueDepth`、`activeConnections`、`bufferPending` 暴露为指标（见 P1-6）。
3. 明确并写入文档：「上行语义 = at-most-once，除非开启 X」——现在这一点只在代码注释里隐含。

### P1-3 离线缓冲是「实例本地内存」，与一致哈希多实例路由天然冲突

**证据**：`DownlinkBuffer` 只在当前 JVM 内存；`DownlinkBufferProperties`/README 已承认「多实例不共享、重启清空」。但多实例下行是按 `deviceId` 一致哈希路由的——设备重连到**另一个实例**时，原实例里暂存的指令永远送不出去（直到 TTL 过期），也没有任何告警。

**建议**
- 短期：为缓冲加「实例归属」校验 + 过期/淘汰指标告警；文档明确「多实例 + 缓冲 = 有损」。
- 中期：改为外置（Redis Stream / 按设备分区的 MQ 延迟队列），把「待补发指令」变成可跨实例、可重启恢复的状态；这同时解决「设备影子持久化」（见 P1-4）。

### P1-4 缺少设备在线/离线事件流与设备影子

**现状**：设备上线/离线只写日志（`ChannelManager.java:80/102`）；业务平台要知道在线状态只能轮询 `GET /api/v1/devices/online`（且无分页，见 P2-2）。

**影响**：这是 IoT 网关的基础能力缺失——平台上无法做「设备离线告警」「上线后同步配置」「按在线率做统计」。轮询在万级设备下也不现实。

**建议**
1. 新增生命周期事件主题（Kafka `iotbridge.device.events`）：`ONLINE / OFFLINE / REGISTERED / KICKED`，含 `deviceId / protocolType / remote / at / reason`。上线以「首帧解码成功」为准，离线以 `channelInactive` + 踢旧连接为准。
2. 设备影子（last state / last seen / 版本）先落 Kafka 由业务侧消费，避免网关自己维护存储；真要网关内维护再考虑 Redis。

### P1-5 下行没有应答闭环，`requestId` 是「死字段」

**现状**：`DownlinkCommand.requestId` 只在日志里出现（`DownlinkDispatcher`、`DownlinkCommandConsumer`）。REST 返回 `requestId` 号称「用于链路追踪」，但**没有任何接口能查这条指令的最终结果**（已下发/设备已确认/失败原因）。

**影响**：上层无法实现「下发—等待设备确认—超时重试」的可靠指令流；消防设备的「指令是否执行」这类问题只能靠人工查日志。

**建议**
1. 定义「下行指令状态机」：`PUBLISHED → DELIVERED → ACKED → FAILED(原因)`，状态变更写入 Kafka（或提供 `GET /api/v1/commands/{requestId}`）。
2. `ProtocolCodec` 增加可选能力：`boolean supportsAck()` / `String correlate(UplinkMessage)`，把上行应答按 `requestId`（或 SOS `seq`）关联回原指令。
3. REST 增加 `Idempotency-Key` 入参，服务端按 key 去重（当前每次调用都会生成新 `requestId`，客户端重试即重复下发）。

### P1-6 完全缺可观测性（上线后等于「盲飞」）

**证据**：全仓 `pom.xml` 无 `spring-boot-starter-actuator`、无 micrometer/prometheus；无 `/actuator/health`；`application.yml` 只有 `logging.level`。

**影响**：无法判断「网关是否健康、哪个环节慢、丢了多少数据、Kafka/Rabbit 是否可达」；容器编排也没有可用的存活/就绪探针。

**建议（成本最低、收益最高的清单）**
1. `spring-boot-starter-actuator` + `micrometer-registry-prometheus`，暴露 `:8081/actuator/health,prometheus`；自定义指标：
   - `iotbridge.uplink.frames{protocol,result}`、`iotbridge.uplink.dropped`
   - `iotbridge.downlink{result=delivered|buffered|dropped|unknown}`
   - `iotbridge.sessions.active{protocol}`、`iotbridge.downlink.buffer.pending`
   - `iotbridge.kafka.send.duration`、`iotbridge.reflow.queue.depth`
2. 日志加 `traceId`（`requestId` / `deviceId` / `protocolType` 进 MDC），让一次下行从 REST → Rabbit → 写通道可在日志里 `grep` 串起来；上行同理（`deviceId` + 采集序号）。
3. 增加 `logback-spring.xml`：控制台 + 文件（滚动、大小上限）、可选 JSON 编码器（便于 ELK/Loki）。
4. 加健康/就绪语义：Netty 是否已 bind、Kafka producer 是否可发、Rabbit 连接是否存活。

### P1-7 优雅停机不排空（SIGTERM 会丢数据）

**证据**
- `UplinkDispatcher.shutdown`（`UplinkDispatcher.java:196-200`）用 `shutdownNow()` → 队列里已接收的帧直接丢。
- `NettyTcpServer.stop`（`NettyTcpServer.java:241-253`）只关 serverChannel 后 `shutdownGracefully()`，没有「先停止接收 → 关闭子通道前 flush → 等待在途写完成」。
- `DownlinkBuffer` 在 `@PreDestroy` 只停清理线程，**不落盘/不等待补发**。

**影响**：滚动发布/缩容时每次都会丢一批上行与在途下行；配了缓冲也白配。

**建议**：实现「停机三段式」——① 从注册中心摘流 + 停止 accept；② 上游队列 drain（带超时）并 flush 在途写；③ 缓冲 flush/落盘（或明确记录丢弃数量）。`SmartLifecycle#stop(Runnable)` 里用 callback 通知容器已排空。

### P1-8 架构分层：`bridge-core` 反向依赖具体传输层

**证据**
- `bridge-core/pom.xml` 直接依赖 `iotbridge-access-netty` 与 `iotbridge-mq`；`UplinkDispatcher` 和 `DownlinkDispatcher` 直接 import `com.iotbridge.access.netty.session.ChannelManager`（返回/接收 Netty `Channel`）。
- 会话契约 `DeviceSessionQuery` 在 `spi`，但「写通道」没有契约——`ChannelManager` 承担了 `sendToDevice`。
- `UplinkFrameListener`/`UplinkFrameEvent` 放在 `access.netty.api`（带 `Channel` 字段），而 CONTRIBUTING 声称「上行通过 **spi** 的 `UplinkFrameListener` 回调」——**文档与代码不一致**。
- `UplinkProducer` 在 `mq.api`（不是 `spi`），`DownlinkDispatchHandler` 在 `spi`——同类契约分散两处。
- `bridge-core` 对 `iotbridge-plugin-demo` 是 `runtime` 依赖：示例协议被带进生产包。

**影响**
- 加第三种接入（UDP/CoAP/HTTP/WebSocket）时，「会话注册 + 下行写入」只能再复制一份，`bridge-core` 会被迫再依赖一个新模块；`access-mqtt` 已经因为复用 `ChannelManager` 而反向依赖 `access-netty`（模块名和职责都对不上）。
- 契约位置不一致会让插件作者和后续维护者反复猜「该实现哪个包」。

**建议（结构性，越早做越便宜）**
1. 把传输抽象上移到 `spi`：
   ```java
   // spi: DeviceSession / SessionRegistry / DownlinkSender
   interface DownlinkSender { void register(SessionKey, DeliveryChannel); boolean send(SessionKey, byte[]); }
   interface DeliveryChannel { String remoteAddress(); boolean active(); boolean writable(); void close(); }
   ```
   Netty 侧用 `Channel` 适配，MQTT 侧复用同一实现；`bridge-core` 只依赖 `spi` + `mq`。
2. `access-netty` 拆成 `access-core`（传输无关的会话/帧事件契约）+ `access-tcp`（Netty TCP）；`access-mqtt` 依赖 `access-core`。
3. `UplinkProducer` / `DownlinkPublisher` 契约迁到 `spi.api`，`mq` 只做实现。
4. 移除 core 对 `plugin-demo` 的依赖（测试需要时用 `test` scope）。

### P1-9 上行消息模型抽象不足，业务平台必须按协议分叉

**现状**：`UplinkMessage` 只有 `deviceId / protocolType / payload(Map) / timestamp`。没有：`command/msgType`、原始帧、接入元数据（远端 IP、来源端口/协议监听口、接入时间、QoS）、消息序号、设备型号。SOS 把 `command` 塞进 payload；MQTT 把非 JSON 载荷塞进 `raw`。

**影响**：业务平台做「按消息类型路由/告警规则/存储表结构」时必须逐协议解析 `payload`；排障时拿不到原始字节（只能改日志级别 + 复现）。这会让网关从「协议归一」退化为「协议透传」。

**建议**：`UplinkMessage` 升级为统一信封：

```json
{
  "deviceId": "...", "protocolType": "sos-c02",
  "messageType": "event|data|register|keepalive",   // 统一枚举 + 协议原始码
  "commandCode": "0x02",
  "ingest": {"remote":"1.2.3.4:5678","listener":"sos","at":1730000000000,"seq":12},
  "payload": {...},
  "rawHex": "可选（按采样率开关，默认关闭）"
}
```

同时给出「协议 → messageType」的映射约定，MQTT/文本协议也按同一信封输出。

### P1-10 下行序号/关联字段未实现（SOS）

**证据**：`SosProtocolCodec.encode` 的 `seq` 取 `params.get("seq")`，缺省 `0`（`SosProtocolCodec.java:206`），网关不生成设备级递增序号；上行 `sequence` 也没用于关联。

**影响**：真实设备若按 `seq` 做请求去重或应答匹配，网关每条下发都是 `seq=0`，设备侧行为不可预期（可能丢帧或误判重放）。

**建议**：每 `(protocol, deviceId)` 维护递增序号（环形 1..65535），`requestId ↔ seq` 映射保留到应答/超时；与 P1-5 的状态机一起做。

---

## 3. P2 — 工程化与持续改进

### P2-1 没有 CI、没有网关镜像、没有构建包装器
- 无 `.github/workflows`、无 `Dockerfile`（`docker/` 只有 Kafka/Rabbit 中间件编排）、无 `mvnw`。
- 建议：CI 至少 `mvn -B verify` + 依赖漏洞扫描（`dependency-check`/Dependabot）+ 构建镜像（多阶段、非 root、`JAVA_OPTS` 可调、`-XX:MaxRAMPercentage`）+ 容器 `healthcheck` 指向 actuator。

### P2-2 REST 面缺基本的产品化能力
- 在线列表无分页/游标（`DeviceQueryController.java:39-43` 一次快照全量返回，10 万设备即 GB 级响应）。
- `POST /commands` 同步阻塞等 Rabbit confirm（`RabbitDownlinkPublisher.java:57-72`，最长 5s）→ Tomcat 线程被占，突发下发会打满线程池；建议改为本地先入队 + 异步 confirm，或独立 `asyncExecutor` + `DeferredResult`。
- 缺审计日志（谁在什么时候给哪台设备发了什么指令）——安全合规必需；缺速率限制；单静态 API Key 无法按调用方区分/轮换/最小权限（读接口与「下发指令」应分离凭据）。
- 无 OpenAPI：接口契约只存在于 README 表格，无法生成 SDK/契约测试。

### P2-3 依赖版本偏旧（安全面）
- Spring Boot `3.2.5`（2024-04，已过 OSS 支持期）；Netty `4.1.100.Final`（2023-04，后续已修复多个安全公告）。
- 建议升级到 Spring Boot 3.3/3.4 LTS 线 + Netty 4.1.11x，并把「季度依赖升级」写进 CONTRIBUTING。

### P2-4 测试覆盖的结构性缺口
- 现有 81 例覆盖了核心逻辑，但**完全没有自动化**的：`admin`（MockMvc 契约/鉴权/错误码映射）、`mq`（Kafka 发送失败、Rabbit confirm nack/return、DLQ 路由）、`starter`（上下文装配）。
- 缺集成测试（Testcontainers：真 Kafka/Rabbit + 真端口跑一遍上下行）；缺边界用例：半包/粘包、超长帧、慢设备（写水位触发）、重复上线踢旧连接后的下行归属、时钟乱序。
- `iotbridge-starter/src/test/java/.../demo/*` 是演示客户端（`main` 方法、无 `@Test`），放在 test 源集但不参与 CI，容易被误认为测试；建议移到 `iotbridge-demo` 模块或 `src/demo`。
- 无压测基线：应给出「N 条连接 / M 帧每秒 / 堆占用 / 各队列深度」的实测表，作为容量规划依据（也是 ≤ 缓冲上限、线程数的推导依据）。

### P2-5 工程卫生
- `iotbridge-spi/src/main/java/.../frame/.DS_Store`、`iotbridge-starter/src/main/resources/.DS_Store` 出现在源码树（虽被 gitignore，但会随打包/IDE 索引污染）。
- 各模块散落 `.project/.classpath/.settings`（未被跟踪但存在），建议统一 `.gitignore` 并清理。
- `.qoder/plans/probridge_gateway_scaffold_*.md` 是内部脚手架计划，不应入库。
- 版本号仍是 `1.0.0-SNAPSHOT`，但 `CHANGELOG.md` 已宣布 `[1.0.0] - 2026-11-05` 发布、且 `[Unreleased]: ./CHANGELOG.md` 链接指向自身——发布流程与版本管理不一致（无 tag）。
- 无代码规范自动化（Checkstyle/SpotBugs/ErrorProne）。CONTRIBUTING 引用了阿里巴巴规约，但没有工具兜底，实际靠人眼。

### P2-6 配置与文档一致性小坑
- `iotbridge.protocol.default` 是「中性兜底」但 `sos` profile 会把它设成 `sos-c02`；而基线又是 `demo-text`。同一份配置在不同 profile 下「默认协议」含义不同，下行未显式带 `protocolType` 时会静默落到不同插件——建议取消「默认协议」这一隐式兜底（下行缺 `protocolType` 直接报错），或由设备注册表推导。
- `spring.kafka.producer.max.in.flight.requests.per.connection: 5` 与幂等生产者并存时需要确认版本语义（幂等 + in-flight>1 在 3.x 下安全，但注释应写清为何是 5）。

---

## 4. 文档评审（重点，逐项给「缺什么 / 怎么补」）

### 4.1 现状诊断

| 文档 | 规模 | 问题 |
| --- | --- | --- |
| `README.md` | 586 行 | 一人承担总览 + 配置手册 + 协议规范 + API 文档 + 测试清单 + 设计说明，已超出 README 承载能力；改一处必然漏改另一处 |
| `PROJECT.md` | — | 与 README 首两节高度重复（同一段"一句话简介"、同一张架构图），双份真相 |
| `docs/deployment.md` | 242 行 | 质量不错，但偏「怎么跑起来」，缺「出事怎么办」（runbook 只有一张排查表） |
| `docs/protocol-sos-c02.md` | 206 行 | 有字节级布局，但自己承认「样例 CRC 为占位」，**没有可执行的基准帧** |
| `CHANGELOG.md` + `docs/change-summary-*.md` ×2 | — | 三处记录同一批变更，语义重叠、必然漂移；change-summary 是「开发过程产物」，不是长期文档 |
| `CONTRIBUTING.md` | 85 行 | 内容扎实，但与代码矛盾（见 4.3） |

**缺失的文档类型**（按价值排序）：架构决策记录（ADR）、API 契约（OpenAPI）、安全与威胁模型、运维 runbook（DLQ 重放/故障处置/扩缩容/回滚）、容量规划与压测报告、协议插件开发指南、协议一致性基准帧库、监控指标目录。

### 4.2 建议的文档骨架

```
README.md                     # 30 秒读懂：是什么/为什么/怎么起/去哪看
docs/
  architecture.md             # 组件职责 + 上下行时序图 + 线程模型 + 部署拓扑（单机/多实例/TLS 终结）
  adr/                        # 一个决策一篇，短
    0001-kafka-uplink-rabbit-downlink.md
    0002-sessionkey-protocol-device.md
    0003-consistent-hash-multi-instance.md
    0004-protocol-codec-spi.md
    0005-uplink-at-most-once-tradeoff.md
  api/
    openapi.yaml              # REST 契约唯一真相，README 只放引用 + 3 个 curl
    errors.md                 # 错误码全集：code / HTTP / 触发条件 / 调用方处置建议
  protocols/
    sos-c02.md                # 现有内容 + 基准帧（hex → 期望 payload 表）
    authoring-guide.md        # 新增协议：模板工程、帧切分参数决策表、编解码契约、测试清单
  operations/
    deployment.md
    runbook.md                # 每条告警/现象 → 判定 → 处置 → 升级路径
    dlq-replay.md             # 死信队列检查与重放（含 curl/管理台步骤、重放前校验）
    capacity-planning.md      # 连接数/帧率/内存/线程/缓冲区上限的推导与实测
    security.md               # 威胁模型、认证/授权规划、密钥轮换、最小权限
  monitoring/
    metrics.md                # 指标目录：名称/类型/标签/含义/建议告警阈值
```

### 4.3 具体待补条目（可直接当 issue 用）

**D1 架构文档缺失**
补 `docs/architecture.md`：模块依赖图（并修正 CONTRIBUTING 里的分层图）、上行/下行时序图（含重试与缓冲分支）、Netty 线程模型（boss/worker/uplink-shard/reflow/MQ consumer 各自职责与队列边界）、多实例拓扑（一致哈希 + 网关实例 + 中间件）。

**D2 文档与代码不一致（必须改）**
- CONTRIBUTING 写「上行通过 **spi** 的 `UplinkFrameListener` 回调」「对外契约统一放在 `spi`」，实际 `UplinkFrameListener`/`UplinkFrameEvent` 在 `access.netty.api`，`UplinkProducer` 在 `mq.api`。
- CONTRIBUTING 分层写 `... → bridge-core → admin → starter`，实际 `bridge-core` 还依赖 `access-netty` 与 `mq`，`admin` 依赖 `mq`。
- README「测试」章节的模块表**漏了 `admin` 与 `mq`**（两者目前没有用例），应显式写「未覆盖及原因」，而不是让读者以为覆盖完整。

**D3 API 契约缺失**
补 `openapi.yaml`（3 个端点 + 全部 DTO + 错误码响应），并把 README 第 6 章改为「契约见 openapi.yaml，示例见下」。当前 README 只给了 2 个端点的 JSON 片段，`DeviceSessionView`、`Result` 结构、错误码集合都要靠猜。

**D4 安全与威胁模型缺失**
补 `docs/operations/security.md`：资产（设备凭证、下行指令、Kafka 数据）、信任边界（公网设备 vs 内网平台）、当前控制项与缺口（对照 P0-1/P0-2）、证书/密钥生命周期、日志脱敏要求。这是客户安全评估必查项。

**D5 运维 runbook 不足**
`deployment.md` 第 8 节是一张 12 行排查表，够「常见问题」，不够「事故处置」。需要补：
- 死信队列怎么查、怎么判定可重放、怎么重放（现在只提了「转入 dlq」，没有后续）。
- 设备大面积离线/反复上下线的判定路径（先看什么指标、再看什么日志、再查什么配置）。
- 多实例扩缩容步骤（新实例队列如何声明、缩容时如何等 DLQ 清空、一致哈希绑定变动的影响）。
- 发布与回滚（配置项差异、队列参数变更导致的 406 PRECONDITION_FAILED 已在文档里踩过一次，应写成「参数变更必须换队列名」的规则）。
- 缓冲开启后的容量与风险说明（何时该开、开了会丢什么）。

**D6 容量规划与压测无基线**
现在所有默认值（`workerThreads=4`、`queueCapacity=10000`、`maxPendingDevices=10000`、写水位 64K、`maxFrameLength=1024`）都**没有推导依据**。补 `capacity-planning.md`：单连接内存模型、每帧成本、目标 QPS/连接数下的实测数据与调整建议。

**D7 协议插件开发指南缺失**
README《扩展新协议》只有 5 步 3 个方法签名，缺关键约定：
- `decode` 遇到非法帧应该抛异常还是返回 null？（当前 `Tests` 依赖抛异常路径，`UplinkDispatcher` 只捕 RuntimeException）
- `encode` 是否允许有副作用/是否必须幂等（补发会重复调用）？
- 帧切分参数（`lengthFieldOffset/Length/Adjustment`）如何从协议文档推导？给出对照表与常见错误样例。
- 插件测试清单（往返自洽、篡改校验、边界长度、ServiceLoader 发现、并发安全）。
- 提供一个 `plugin-template` 模块（空实现 + 测试骨架），把「新建协议」从 1 小时降到 10 分钟。

**D8 协议基准帧缺失**
`docs/protocol-sos-c02.md` 的样例 CRC 是占位，测试用「自造帧 + 回解析自洽」——这只证明**自身一致**，不证明**符合厂商协议**。补一份 `frames/` 基准库（每个命令一条真实 hex + 期望 JSON），并加一条参数化测试。这是与厂商联调时唯一能定责的东西。

**D9 变更记录三处并存**
`CHANGELOG.md` 保留（对外），`docs/change-summary-*.md` 合并进 `CHANGELOG.md` 后删除（或移入 `docs/adr/` 作为决策背景）。同一事实只留一处。

**D10 README 拆分与双语**
- README 收到 ~150 行（是什么/特性/1 分钟启动/架构一图/文档索引/许可），其余下沉到 `docs/`。
- 开源项目建议 `README.en.md`；`PROJECT.md` 与 README 重复部分删除或改为 `docs/architecture.md` 的摘要。

**D11 配置参考缺「默认值从哪来、改了什么后果」**
README 第 10 章是配置表，但缺少：影响的链路（上行/下行/会话）、失败模式（改错的症状）、是否需重启、与其它配置的联动约束（如 `read-idle` 必须大于设备心跳；`buffer-enabled` 与多实例的冲突）。建议改成「配置项 → 默认 → 生效链路 → 调错的症状」四列。

**D12 无出错时的「自检清单」**
部署后 5 分钟自检（端口、协议校验日志、Kafka 首条消息、Rabbit 队列深度、在线列表、DLQ 为 0）——这是把「文档」变成「可执行验收」的最省事一步，可写成 `scripts/smoke-test.sh` 并在文档里引用。

---

## 5. 建议的推进顺序（如果要排期）

**第一波（1–2 周，安全与正确性，不改架构）**
1. P0-3 下行三态语义 + 幂等键（改动小、风险收益比最高）
2. P0-2 安全默认值反转（MQTT 默认关、prod 空 key 启动失败）
3. P1-1 补发入口去重 + 有界队列
4. P1-6 actuator + 核心 6 个指标 + MDC traceId + logback 配置
5. D2 文档纠偏（低成本、避免误导后续开发者）

**第二波（3–6 周，能力补齐）**
6. P0-1 设备认证 + 白名单（需要产品定义，先做 `device-secret` 方案）
7. P1-4 设备在线/离线事件流；P1-5 下行状态机与 `requestId` 查询接口
8. P1-7 停机排空；P0-4 连接数/速率限制
9. P2-1 CI + Dockerfile + 依赖升级；Testcontainers 集成测试
10. D3 OpenAPI、D5 runbook、D7 插件开发指南 + 模板

**第三波（结构性，需排期评审）**
11. P1-8 传输抽象上移 `spi`（新增 `SessionRegistry/DownlinkSender`），`access-netty` 拆分
12. P1-3 缓冲外置 + 设备影子持久化（配合多实例扩容）
13. P1-9 统一上行信封（对业务侧是破坏性变更，需与下游约定迁移窗口）
14. D6 容量规划 + 压测基线；D8 基准帧库

---

## 6. 附：低成本高收益的「立刻可做」清单

- [ ] `sendToDevice` 返回三态，超时不再进缓冲（P0-3）
- [ ] `reflow` 入口判 `pendingSize`，线程池换有界队列（P1-1）
- [ ] `KafkaUplinkProducer` 失败计数 + 暴露指标（P1-2）
- [ ] MQTT `enabled` 默认 `false`；`prod` 下空 `api-key` 启动失败（P0-2）
- [ ] `maxConnections` 加到 TCP 接入（P0-4）
- [ ] `UplinkMessage` 增加 `messageType` + `ingest`（向后兼容字段，不破坏现有消费方）（P1-9 第一步）
- [ ] `logback-spring.xml` + `traceId` MDC（P1-6）
- [ ] README 测试表补 `admin`/`mq` 的「未覆盖」说明；修正 CONTRIBUTING 分层图（D2）
- [ ] 删除 `.DS_Store`、`.qoder/plans`，合并 change-summary 到 CHANGELOG（P2-5/D9）

---

## 7. 第一批落地情况（2026-11-14）

按第 5 节「第一波」执行，已完成以下 5 项；其余项仍按优先级排队。

| 条目 | 状态 | 落地要点 |
| --- | --- | --- |
| P0-3 下行三态语义 + 幂等键 | ✅ 已完成 | `ChannelManager.sendToDevice` 返回 `DeliveryResult`；`UNKNOWN`（写等待超时/中断）不进暂存、不重投；`DownlinkIdempotencyCache` 按 `(protocol, device, requestId)` 对「已送达」去重；REST 支持 `Idempotency-Key`（confirm 成功后才记忆） |
| P0-2 安全默认值反转 | ✅ 已完成 | `iotbridge.mqtt.enabled` 默认 `false`（代码默认、`matchIfMissing`、`application.yml`、启动校验四处同步）；`ProductionSecurityGuard` 在 `prod` 下缺 `api-key` 或 MQTT 匿名时**启动失败** |
| P1-1 补发入口去重 + 有界队列 | ✅ 已完成 | 提交前判 `pendingSize`；同会话 in-flight 去重；`ThreadPoolExecutor` + `ArrayBlockingQueue(reflow-queue-capacity)` + 拒绝计数 |
| P1-6 actuator + 6 指标 + MDC + logback | ✅ 已完成 | `/actuator/health`（含 liveness/readiness）与 `/actuator/prometheus`；`BridgeMetrics` 六类指标；`TraceIdFilter` + 三处 MDC；`logback-spring.xml`（控制台 + 滚动文件） |
| D2 文档纠偏 | ✅ 已完成 | CONTRIBUTING 分层图/契约位置与实际代码对齐并标注「已知待改点」；README 补三态/幂等/MQTT 默认关/计量端点/测试数（81→101）与 `iotbridge-mq` 缺口；新增 `docs/monitoring/metrics.md`、部署文档补安全基线/探针/排查项 |

验证：`mvn -o test` 101 例全绿；JDK 17 打包后启动冒烟通过（`/actuator/health`=UP、prometheus 指标可查、1883 不再监听、`X-Trace-Id` 透传、幂等键重复提交只投放一次、`prod` 缺 key 启动即失败）。

**尚未做（仍是最高优先级）**：P0-1 设备身份认证（当前 TCP 设备可被冒名顶替）、P0-4 TCP 连接数与速率限制、P1-5 指令应答闭环（`requestId` 仍无状态查询）、P1-7 停机排空、P1-8 传输抽象上移 `spi`、P1-9 统一上行信封。

---

## 8. P0-1 落地情况（2026-11-14，第一轮）

| 子项 | 状态 | 说明 |
| --- | --- | --- |
| 设备注册表（`(protocol, deviceId)` 维度） | ✅ 已完成 | `iotbridge.security.devices`，支持启用/吊销、设备级密码、证书指纹、来源 IP（精确 + 前缀通配）；键与下行寻址同口径 |
| TCP 接入校验 | ✅ 已完成 | `UplinkDispatcher` 在 `ChannelManager.register` **之前**校验，未通过不登记会话（因此不可被下行寻址），强制模式关闭连接 |
| MQTT 接入校验 | ✅ 已完成 | `MqttConnectionHandler` 在 CONNACK 前按 clientId 校验设备级密码，解决“知道全局密码即可冒用任意 clientId” |
| 灰度与强制 | ✅ 已完成 | `enforce=false` 观察（告警+指标）/ `true` 拒绝；`prod` 下强制，否则 `ProductionSecurityGuard` 拒绝启动 |
| 审计与观测 | ✅ 已完成 | `iotbridge_security_auth_total{protocol,result}` 六类结果 + WARN 日志（含 protocol/deviceId/remote/result） |
| **TCP 强身份（mTLS）** | ⛔ 未完成（下一轮） | Netty 侧未挂 `SslHandler`；`DeviceCredential.certFingerprint` 入参与注册表指纹比对已就绪，接入后即可生效 |
| **协议内认证（固件配合）** | ⛔ 未完成（待定） | SOS-C02 帧无认证字段；若要 HMAC 挑战应答需设备固件支持，需产品侧确认 |

**结论**：本轮把「未登记设备」和「凭据不符设备」挡在门外，MQTT 达到设备级身份；TCP 仍是「白名单 + IP 绑定」的弱身份，强身份依赖 mTLS，尚需一轮。
