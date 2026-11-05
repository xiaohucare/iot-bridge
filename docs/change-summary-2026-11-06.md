# iotbridge 可靠性/安全修复变更摘要

- **日期**：2026-11-06
- **基线**：`d3fbd75 升级1.1`
- **范围**：26 个文件（+701 / −254），新增 4 个类、删除 2 个文件
- **验证**：`mvn test` 全量通过，12 个测试类 / **63 个用例**，0 失败 0 错误 0 跳过

## 一、修复清单

### 1. 上行处理移出 Netty I/O 线程

**问题**：解码、会话登记、Kafka 投递都在 EventLoop 上执行，Kafka 阻塞最长可达 `max.block.ms`（默认 60s），broker 抖动会拖垮整条 I/O 线程。

**变更**：

- `UplinkDispatcher` 入站回调只做「按 `remoteAddress` 哈希选分片 + 提交任务」；
- 每个分片为单线程有界队列（默认 4 分片、总队列 10000，`AbortPolicy`），同连接帧在固定分片内串行保序，分片间并行；
- 队列满时丢弃并每 1024 帧采样告警（`droppedFrames`），不阻塞 I/O；
- 新增 `UplinkProperties`（`iotbridge.uplink.*`），Kafka 生产端显式设置 `max.block.ms=3000`。

**文件**：`UplinkDispatcher.java`、`UplinkProperties.java`（新）、`BridgeCoreConfig.java`、`application.yml`

### 2. 连接活性检测（半开连接）

**问题**：无空闲检测，设备断电/NAT 超时后 `isActive()` 长期为真，在线状态不可信，下行写入长期排队。

**变更**：

- `NettyTcpServer` 在 `reader-idle-seconds > 0`（默认 180s）时装配 `IdleStateHandler`；
- `DeviceInboundHandler.userEventTriggered` 收到 `IdleStateEvent` 主动 `close()` 并告警。

**文件**：`NettyProperties.java`、`NettyTcpServer.java`、`DeviceInboundHandler.java`

### 3. 多实例下行路由

**问题**：`DirectExchange` + 固定 routing key，多实例各自绑定队列 = 同键广播，造成重复下发与随机错投。

**变更**：

- 新增 `iotbridge.mq.downlink-exchange-type`（`direct` / `consistent-hash`）与 `instance-id`；
- `consistent-hash` 模式下交换机为 `x-consistent-hash`，routing key 用 `deviceId`，队列名为 `<downlink-queue>.<instance-id>`（缺省取 `HOSTNAME`），死信拓扑同源派生；
- 发布端按 `effectiveRoutingKey(deviceId)` 发送；confirm 失败/超时返回 `B0300`。

**文件**：`MqProperties.java`、`RabbitConfig.java`、`RabbitDownlinkPublisher.java`、`DownlinkCommandConsumer.java`

> 前置条件：RabbitMQ 需启用 `rabbitmq_consistent_hash_exchange` 插件。会话仍为实例内存态，未实现集群会话共享。

### 4. REST 鉴权

**问题**：`/api/**` 无任何鉴权，任意调用方可下发设备指令。

**变更**：

- 新增 `AdminSecurityProperties`（`iotbridge.admin.api-key`）与 `ApiKeyAuthFilter`；
- 支持 `X-Api-Key` 与 `Authorization: Bearer`，`MessageDigest.isEqual` 常量时间比较；
- 仅拦截 `/api/*`，放行 OPTIONS；失败返回 401 + `A0301`；
- 未配置 key 时启动 WARN（保持本地开发可用）；
- 文档补充 TLS 边界说明：REST 侧走 `server.ssl.*`，设备侧 9000/9001 仍为明文 TCP。

**文件**：`AdminSecurityProperties.java`（新）、`ApiKeyAuthFilter.java`（新）、`AdminConfig.java`（新）、`ErrorCodeEnum.java`、`application.yml`

### 5. 下行写成功语义与补发并发

**问题**：只判断 `isActive()`，通道写缓冲已满仍当作成功；补发单线程，慢设备阻塞其他设备补发。

**变更**：

- `ChannelManager.sendToDevice` 增加 `isWritable()` 检查，不可写按未送达返回 `false`（交给离线暂存策略）；
- `DownlinkReflowService` 改为线程池（`iotbridge.downlink.reflow-threads`，默认 4），不同设备并行、同会话用 256 段固定锁串行；
- 锁不再按 key 常驻 map，内存恒定、无回收问题。

**文件**：`ChannelManager.java`、`DownlinkReflowService.java`、`DownlinkBufferProperties.java`

### 6. 消除每帧日志与重复登记开销

**问题**：`ChannelManager.register` 每帧重复写 map 并打 INFO，量级上去后日志与锁争用明显。

**变更**：同一 `Channel` 早返回，仅在首次绑定、重复上线踢旧连接、下线时输出日志。

**文件**：`ChannelManager.java`

### 7. 启动期配置校验与资源释放

**问题**：默认协议/监听协议写错要等首帧才报错；端口非法或重复绑定的失败信息不明确；部分绑定失败后已绑定端口未释放。

**变更**：

- 新增 `ProtocolStartupValidator`（`InitializingBean`）：校验默认协议与各 listener 的 `protocol-type` 均已注册，否则启动失败；
- `NettyTcpServer` 校验端口 1–65535、监听地址不重复；
- 写缓冲水位校验 `0 < low < high`；
- `start()` 失败时 `catch` 后 `stop()` 释放已绑定端口。

**文件**：`ProtocolStartupValidator.java`（新）、`NettyTcpServer.java`

### 8. 业务异常错误码与 HTTP 状态

**问题**：所有异常被统一包成 `SYSTEM_ERROR` 且 HTTP 一律 200，调用方无法区分「参数错」「设备离线」「发布失败」。

**变更**：

- `GlobalExceptionHandler` 保留原始业务码，按类别映射状态：`A0301`→401、`A04xx`/协议不存在/编解码失败→400、`DEVICE_OFFLINE`→409、`DOWNLINK_PUBLISH_FAILED`→503、其余 500；
- 新增 `UNAUTHORIZED(A0301)`、`DOWNLINK_PUBLISH_FAILED(B0300)`；`Result.fail(code, message)` 便捷方法。

**文件**：`GlobalExceptionHandler.java`、`ErrorCodeEnum.java`、`Result.java`

### 9. 清理与容量淘汰优化

**变更**：

- 删除死代码 `FrameRule` 与残留配置 `application copy.yml`，移除 `IotbridgeConstants.FRAME_DELIMITER`；
- `DownlinkBuffer` 容量淘汰从「全表扫描找最旧」改为设备顺序队列 + 摊还压缩，避免每次淘汰 O(设备数)；
- README 测试数修正（37 → 63）、补充新配置项与线程模型说明；`docs/deployment.md` 新增鉴权/多实例/故障排查内容。

**文件**：`FrameRule.java`（删）、`application copy.yml`（删）、`DownlinkBuffer.java`、`IotbridgeConstants.java`、`README.md`、`docs/deployment.md`

## 二、新增/变更配置

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `iotbridge.netty.reader-idle-seconds` | 180 | 读空闲超时（秒），`<=0` 关闭检测 |
| `iotbridge.netty.write-buffer-high/low-water-mark` | 65536 / 32768 | 出站写缓冲水位（字节） |
| `iotbridge.uplink.worker-threads` | 4 | 上行为处理分片数 |
| `iotbridge.uplink.queue-capacity` | 10000 | 上行待处理帧总队列容量 |
| `iotbridge.admin.api-key` | 空 | REST API Key，为空不鉴权（启动告警） |
| `iotbridge.mq.downlink-exchange-type` | `direct` | `consistent-hash` 用于多实例 |
| `iotbridge.mq.instance-id` | 空 | 缺省取 `HOSTNAME` |
| `iotbridge.downlink.reflow-threads` | 4 | 离线补发线程数 |
| `spring.kafka.producer.max.block.ms` | 3000 | 上行 Kafka 最大阻塞时长 |

## 三、行为变化（升级注意）

1. **REST 调用方**：启用 `api-key` 后必须带 `X-Api-Key`；HTTP 状态码不再恒为 200，客户端需按 4xx/5xx 处理。
2. **日志量**：`com.iotbridge` 默认级别由 `DEBUG` 调整为 `INFO`，且不再每帧打印「设备上线」。
3. **多实例**：必须切换 `consistent-hash` 并启用插件，否则同一指令会在各实例队列重复投递。
4. **不兼容配置**：`direct` 模式下队列名保持 `iotbridge.downlink`；切换到 `consistent-hash` 会创建新队列 `<queue>.<instance>`，原队列中未消费的指令需人工确认。
5. **离线补发**：改为多线程并行，同设备仍严格串行，顺序语义不变。

## 四、验证结果

| 模块 | 测试类 | 用例 |
| --- | --- | --- |
| plugin-demo | DemoTextCodecTest | 3 |
| plugin-sos | Crc16XmodemTest / SosProtocolCodecTest | 2 / 9 |
| access-netty | NettyTcpServerTest | 7 |
| bridge-core | ProtocolStartupValidatorTest / ProtocolCodecRegistryTest | 4 / 3 |
| bridge-core | ChannelManagerTest / DownlinkBufferTest | 9 / 10 |
| bridge-core | DownlinkDispatcherTest / DownlinkReflowServiceTest / UplinkDispatcherTest / DeviceSessionQueryAdapterTest | 5 / 5 / 3 / 3 |

合计 **63** 个用例，`mvn test` 退出码 0。

## 五、仍存在的限制

- 会话状态仅存于单实例内存，实例宕机期间依赖 MQ 队列留存，无集群会话共享；
- 设备接入端口为明文 TCP，链路加密需前置 TLS 终结或专用安全网关；
- 未内置设备级鉴权、指令幂等与应答闭环、设备影子持久化、指标监控。
