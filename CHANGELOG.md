# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **下行三态投递语义**：`DeliveryResult`（`DELIVERED` / `UNKNOWN` / `NOT_DELIVERED`）替代 `ChannelManager.sendToDevice` 的布尔返回。写等待超时/线程中断判定为 `UNKNOWN`——数据可能已写出，**不进入离线暂存、不重投**；只有确定未送达才暂存补发，从根上消除「超时重投」导致的设备重复执行。
- **下行投递幂等**：`DownlinkIdempotencyCache` 按 `(protocolType, deviceId, requestId)` 记忆已确认送达的指令，MQ 重投/REST 重试产生的重复指令直接跳过并计数；补发前同样校验，避免重复执行。
- **REST 幂等键**：`POST /api/v1/devices/{deviceId}/commands` 支持 `Idempotency-Key` 请求头（8–128 位字母数字或 `_ . : -`），同设备同键在窗口内重复提交返回首次 `requestId` 且不再投放；记忆在 broker confirm 成功后写入，失败可安全重试。
- **可观测性**：接入 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`，暴露 `/actuator/health`（含 liveness/readiness 探针）与 `/actuator/prometheus`；新增 `BridgeMetrics` 六类核心指标（上行帧/丢帧、下行结果、在线会话、暂存待发、补发结果），文档见 `docs/monitoring/metrics.md`。
- **日志链路字段与滚动文件**：新增 `logback-spring.xml`（控制台 + 100MB/30 天/2GB 上限滚动文件）；REST `TraceIdFilter` 生成或透传 `X-Trace-Id` 并回写响应头，上行/下行/补发线程写入 `deviceId`/`protocolType`/`requestId` MDC。
- **生产安全基线**：`ProductionSecurityGuard` 在 `prod` profile 下校验 `iotbridge.admin.api-key` 非空、MQTT 启用时必须配置 `username`、设备注册表必须启用且强制，不满足直接启动失败。
- **通用有界 TTL 缓存**：`BoundedTtlCache`（过期先清理、超限拒绝并计数），供两处幂等去重使用，内存不会无界增长。

- **设备接入认证（P0-1）**：新增 SPI 契约 `DeviceAuthenticator` / `DeviceCredential` / `DeviceAuthResult` 与 `iotbridge.security` 设备注册表（`(protocol, deviceId)` 维度，支持启用/吊销、设备级密码、客户端证书指纹、来源 IP 绑定）。TCP 在会话登记前校验、MQTT 在 CONNACK 前校验；未通过时强制模式断链 / 回 CONNACK 5，观察模式只告警。默认观察模式，`prod` 必须 `enforce=true`（否则启动失败）。
- **认证指标**：新增 `iotbridge_security_auth_total{protocol,result}`（allowed/notConfigured/unknownDevice/disabled/badCredential/networkDenied），并接入告警建议。

### Changed

- **接口/契约变更**：`ChannelManager.sendToDevice(...)` 返回 `DeliveryResult`（原 `boolean`）；`DownlinkDispatcher` / `DownlinkReflowService` / `UplinkDispatcher` 构造新增幂等缓存与指标参数（保留旧构造重载以兼容既有调用与测试）。
- **MQTT 接入默认关闭**：`iotbridge.mqtt.enabled` 默认值由 `true` 改为 `false`（`MqttServer`/`MqttPayloadCodec` 的 `matchIfMissing` 同步改为 `false`），避免默认向公网暴露 1883 明文监听面；基线 `application.yml` 显式写为 `false`。
- **补发线程池有界化**：`reflow` 改用 `ThreadPoolExecutor + ArrayBlockingQueue(reflow-queue-capacity)` + `AbortPolicy`，队列满拒绝并计数；提交前先判断该会话是否有待发指令，同一会话任务以 in-flight 标记去重，避免心跳帧持续堆积空任务。
- **补发三态处理**：补发遇 `UNKNOWN` 只退回其后帧，当前帧按「至多一次」处理（原实现会连同当前帧一起退回，存在重复执行风险）。

### Fixed

- **缺少指标注册表时不再拒绝启动**：`BridgeMetrics` 改为经 `ObjectProvider<MeterRegistry>` 注入，运行环境未引入 `spring-boot-starter-actuator`（如 IDE 未刷新 Maven 依赖的精简 classpath）时只告警并降级为空操作，修复 `Parameter 0 of constructor in BridgeMetrics required a bean of type MeterRegistry` 导致的启动失败。

### Docs

- README 新增「关于本项目」块（定位、适用/暂不适用、项目状态、许可证），并补 §15 许可证章节；清理文末与 §4 重复的 docker 命令片段。
- 新增 `docs/README.md` 文档索引，按「想跑起来 / 想部署 / 想接新协议 / 想了解设计不足 / 历史变更」分流全部文档，并写明文档同步维护约定。

- 修正 `CONTRIBUTING.md` 分层图与实际依赖（`bridge-core` 依赖 `mq` 与 `access-netty`，`access-mqtt` 依赖 `access-netty`），并明确 `UplinkFrameListener`（`access-netty.api`）/`UplinkProducer`（`mq.api`）/`spi` 契约的实际位置与已知待改点。
- README 补充 `Idempotency-Key`、三态与幂等说明、MQTT 默认关闭、新增配置项、计量端点、测试用例数（81 → 101）与 `iotbridge-mq` 自动化缺口。
- 新增 `docs/monitoring/metrics.md`（指标语义、PromQL、告警建议）；`docs/deployment.md` 增加生产安全基线、设备接入认证（含灰度步骤与 TCP 身份边界）、健康检查与指标、日志字段与新增排查项。

## [1.0.0] - 2026-11-05

首个功能版本：全双工 IoT 协议转换网关骨架与核心能力。

### Added

- **工程骨架**：`com.iotbridge:iotbridge` Maven 多模块（JDK 17 + Spring Boot 3.2.5），分层为 `common` / `spi` / `plugin-demo` / `plugin-sos` / `mq` / `access-netty` / `bridge-core` / `admin` / `starter`，自底向上、无循环依赖。
- **Netty TCP 接入**：`NettyTcpServer` 引导 boss/worker，支持 `DELIMITER`（行分隔文本）与 `LENGTH_FIELD`（长度字段二进制）两种切帧模式，可配置切换。
- **SPI 协议插件化**：`ProtocolCodec` 契约 + `META-INF/services` 注册，`ProtocolCodecRegistry` 合并 ServiceLoader 与 Spring Bean 自动发现，按 `protocolType` 路由。
- **示例协议插件**：`demo-text` 行文本协议，作为最小可运行示例。
- **SOS 消防协议插件**：`sos-c02`（SOS-C02 / C03B / W03-A / C03-AO V3.0）二进制帧 + CRC16-XMODEM 编解码。
- **全双工转发**：上行 `设备 --TCP--> Kafka`（`KafkaUplinkProducer`），下行 `REST --RabbitMQ--> 设备`（`RabbitDownlinkPublisher` + `DownlinkCommandConsumer`）。
- **REST 接口**：下行指令下发 `POST /api/v1/devices/{deviceId}/commands`；在线设备查询 `GET /api/v1/devices/online` 与单设备在线判定 `GET /api/v1/devices/{deviceId}/online`。
- **下行离线暂存重投（可选）**：`iotbridge.downlink.buffer-enabled` 开启后按会话暂存**原始指令**（单设备有界 + 设备数上限 + TTL），设备重新上线时按会话串行、**重新编码**后按序补发；发送失败退回剩余帧，杜绝静默丢失。
- **多协议多监听**：`iotbridge.netty.listeners[]` 按端口绑定协议与切帧方式，单实例可并行接入多种协议；未配置时以扁平字段合成单一监听（向后兼容）。
- **会话按 (协议, 设备号) 隔离**：新增 `SessionKey(protocolType, deviceId)` 复合键，贯穿 `ChannelManager`、下行缓冲与补发，不同协议下的相同 `deviceId` 各自独立、互不覆盖。
- **在线查询按协议过滤**：`GET /api/v1/devices/online` 支持可选 `?protocol=`；单设备在线支持聚合与精确两种判定；`DeviceSessionView` 暴露 `protocolType`。
- **连接日志**：设备建立 TCP 连接即打印 `设备连上服务 remote={}`，与 `deviceId` 级「设备上线/离线」区分。
- **端到端联调脚本**：`scripts/e2e-session-isolation.sh`，以相同设备号经两协议端口接入并自动断言会话隔离与下行各自送达。
- **优雅生命周期**：`GatewayLifecycle`（`SmartLifecycle`）随容器自动启停 Netty。

### Changed

- **默认协议与监听**：基线 `application.yml` 默认协议设为 `sos-c02`、切帧默认 `LENGTH_FIELD`；默认多监听 `9000=sos-c02`、`9001=demo-text`，无需显式激活 profile 即可运行。

### Fixed

- **下行消费者队列读取配置**：`DownlinkCommandConsumer` 的 `@RabbitListener` 由硬编码常量改为读取 `iotbridge.mq.downlink-queue`（缺省回退常量），使队列声明、消费监听与发布路由同源；修复多网关实例默认挤占同一队列导致的下行投递被抢问题，并支持为单实例指定独立队列。

[Unreleased]: ./CHANGELOG.md
[1.0.0]: ./README.md
