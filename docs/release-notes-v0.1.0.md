# iotbridge v0.1.0 发行说明

首个可用版本：单实例可跑通「设备接入 → 解码 → Kafka 上行 → 平台指令 → 设备回写」全链路，并把安全与可观测性补齐到可以对外试用。

## 主要能力

- **双接入方式**：Netty TCP（行分隔 / 长度字段两种帧切分）与内置 MQTT 3.1.1 服务端，设备以 clientId 作设备号。
- **协议插件化**：`ProtocolCodec` SPI + `META-INF/services` 自动发现，内置 `demo-text`（文本示例）与 `sos-c02`（SOS-C02 V3.0 消防协议，二进制帧 + CRC16）。
- **全双工转发**：上行投递 Kafka（`acks=all` + 幂等生产者），下行 REST → RabbitMQ → 设备，发布端等 broker confirm。
- **会话模型**：以 `(protocolType, deviceId)` 复合键隔离，同一设备号在不同协议下互不覆盖，重复上线顶替旧连接。

## 本版重点：安全与正确性

- **下行三态投递**：`DELIVERED` / `NOT_DELIVERED` / `UNKNOWN`。等待超时不再误判为失败而重投，避免设备重复执行同一条指令。
- **两级幂等**：投递级按 `(protocolType, deviceId, requestId)` 去重；REST 支持 `Idempotency-Key`，重复提交只投放一次。
- **补发有界化**：离线暂存补发改用有界队列 + 在途去重，队列满采样告警而非无限堆积。
- **安全默认值反转**：MQTT 默认关闭；`prod` 下 api-key 为空、MQTT 启用却无凭据、设备认证未强制，均拒绝启动。
- **设备接入认证**：`iotbridge.security.devices` 注册表按 `(协议, 设备号)` 校验密码 / 证书指纹 / 来源 IP；TCP 在登记会话**之前**认证（未通过者不会被下行寻址），MQTT 在 CONNACK 前认证并回 `0x05`。默认观察模式，`prod` 必须强制。
- **可观测性**：`/actuator/health`（含 liveness/readiness）+ `/actuator/prometheus`；8 个核心指标（上行帧、丢帧、下行结果、在线会话、缓冲待发、补发结果、认证结果）；MDC 携带 `traceId`/`deviceId`/`requestId`，日志按大小滚动落盘；缺少 `MeterRegistry` 时指标降级为空操作而非拒绝启动。

## 升级 / 首次部署注意

1. `iotbridge.mqtt.enabled` 默认 `false`，需要 MQTT 接入必须显式开启并配 `username`/`password`。
2. `prod` profile 下三项基线必须满足：`iotbridge.admin.api-key` 非空、MQTT 启用时凭据齐全、`iotbridge.security.enabled=true` 且 `enforce=true`。
3. 设备注册表为空白时视为「谁都不信」，上线前先把存量设备登记进 `iotbridge.security.devices`，建议先 `enforce=false` 观察指标与告警，再切强制。
4. 多实例部署必须用一致哈希下行路由（`downlink-exchange-type=consistent-hash`），否则同键多队列会广播导致重复下发。

## 已知限制

- 设备侧强身份未完全落地：TCP 仍为「注册表白名单 + 来源 IP」，mTLS 待接入（Netty 侧证书指纹钩子已预留）。
- 无指令应答闭环（设备 ACK 与 `requestId` 状态查询）、无设备影子持久化。
- 离线缓冲与会话为实例本地内存，多实例不共享。
- 链路明文（TCP 9000/9001、MQTT 1883），需加密请在网关前置 TLS 终结。
- MQTT 仅 QoS0/1，不支持 MQTT 5.0、retained、Will。

## 文档

- 文档索引：[docs/README.md](docs/README.md)
- 部署与运维：[docs/deployment.md](docs/deployment.md)（含生产安全基线、设备接入认证、故障排查表）
- 监控指标：[docs/monitoring/metrics.md](docs/monitoring/metrics.md)
- 设计评审与已知不足：[docs/design-review-2026-11-14.md](docs/design-review-2026-11-14.md)
