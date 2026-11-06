# iotbridge MQTT 接入变更摘要

- **日期**：2026-11-06
- **主题**：新增 MQTT 3.1.1 设备接入（轻量 Broker）
- **验证**：`mvn test` 全量通过 —— 15 个测试类 / **79 个用例**，0 失败 0 错误（其中 MQTT 新增 13 个）

## 一、总体设计

MQTT 不作为字节级协议插件接入，而是作为**独立接入层**（与 TCP 平级），复用既有会话与分发链路：

```
设备 --MQTT--> MqttServer (MqttDecoder/MqttEncoder)
                └ MqttConnectionHandler: CONNECT -> ChannelManager.register(protocol=mqtt, deviceId=clientId)
                                          PUBLISH -> UplinkFrameEvent(deviceId=clientId) -> UplinkDispatcher
                                                     -> MqttPayloadCodec.decode -> Kafka
                                          SUBSCRIBE -> 记录主题过滤器

平台 --REST--> RabbitMQ -> DownlinkDispatcher -> MqttPayloadCodec.encode(JSON) -> ChannelManager.sendToDevice
                └ MqttDownlinkWriteHandler: ByteBuf -> PUBLISH(下行主题) -> 设备
```

因此 MQTT 设备与 TCP 设备共享：在线查询、下行寻址、离线暂存/补发、协议路由与错误码体系。

## 二、新增文件

### 新模块 `iotbridge-access-mqtt`

| 文件 | 职责 |
| --- | --- |
| `pom.xml` | 依赖 common / spi / access-netty / netty-all / spring-boot-starter |
| `config/MqttProperties.java` | `iotbridge.mqtt` 配置：开关、监听、主题前缀、QoS、鉴权、保活、写缓冲水位 |
| `config/MqttAccessConfig.java` | 启用配置绑定 |
| `server/MqttServer.java` | Netty 服务端；配置校验、绑定失败释放资源；实现 `AccessServer`；测试可读实际端口 |
| `handler/MqttConnectionHandler.java` | CONNECT 校验/鉴权/注册、PUBLISH 上行、SUBSCRIBE/UNSUBSCRIBE、PINGREQ、空闲断链、断开清理 |
| `handler/MqttDownlinkWriteHandler.java` | 位于编码器前，把下行字节包装为 PUBLISH；未订阅则写失败，交由离线策略 |
| `handler/MqttChannelAttributes.java` | 通道属性：clientId、下行主题、订阅集合、QoS1 报文序号 |
| `codec/MqttPayloadCodec.java` | 上行 JSON/非 JSON 载荷映射、下行 `{command,params,requestId}` 编码 |
| `util/MqttTopicMatcher.java` | 主题过滤器 `+` / `#` 匹配 |

### 新增接入契约

| 文件 | 说明 |
| --- | --- |
| `iotbridge-access-netty/api/AccessServer.java` | 接入服务端统一契约（`start` / `stop`），TCP 与 MQTT 均实现 |
| `iotbridge-access-mqtt/src/test/...` | `MqttServerTest`(3)、`MqttTopicMatcherTest`(6)、`MqttPayloadCodecTest`(4) |

## 三、对既有模块的改动

| 文件 | 改动 |
| --- | --- |
| `pom.xml` | 新增模块；`dependencyManagement` 增加 `iotbridge-access-mqtt` |
| `iotbridge-starter/pom.xml` | 引入 MQTT 接入模块 |
| `iotbridge-starter/src/main/resources/application.yml` | 新增 `iotbridge.mqtt.*` 配置块 |
| `access-netty/.../UplinkFrameEvent.java` | 新增可选 `deviceId`（接入层已知标识，如 clientId） |
| `access-netty/.../NettyTcpServer.java` | 实现 `AccessServer`（行为不变） |
| `bridge-core/.../UplinkDispatcher.java` | 设备标识优先取 `event.deviceId`，缺失再回退解码结果；写回 `UplinkMessage` 保证 Kafka 消息一致 |
| `bridge-core/.../GatewayLifecycle.java` | 由单 TCP 启动改为启动/停止全部 `AccessServer`，启动失败回退已启动服务 |
| `bridge-core/.../ProtocolStartupValidator.java` | 新增 MQTT 协议注册校验（`iotbridge.mqtt.enabled` + `protocol-type`） |
| `bridge-core/.../ProtocolStartupValidatorTest.java` | 4 → 7 个用例（MQTT 缺失失败、关闭跳过、已注册通过） |
| `README.md` | 特性、架构图、模块树、快速开始、7.2 MQTT 接入、配置表、测试数 63→79、设计限制 |
| `docs/deployment.md` | 4.5 MQTT 接入、环境变量、TLS/鉴权提示、故障排查项 |

## 四、新增配置

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `iotbridge.mqtt.enabled` | true | false 时完全不绑定端口 |
| `iotbridge.mqtt.host` / `port` | 0.0.0.0 / 1883 | MQTT 监听地址与端口 |
| `iotbridge.mqtt.protocol-type` | mqtt | 会话键与下行指令使用的协议标识 |
| `iotbridge.mqtt.max-message-size` | 65536 | 单条报文上限 |
| `iotbridge.mqtt.uplink-topic-prefix` | iotbridge/uplink | 该前缀下（含自身）的 PUBLISH 视为上行 |
| `iotbridge.mqtt.downlink-topic-prefix` | iotbridge/downlink | 实际下行主题 `<前缀>/<clientId>` |
| `iotbridge.mqtt.qos` | 0 | 下行 PUBLISH QoS，仅 0/1 |
| `iotbridge.mqtt.username` / `password` | 空 | 非空启用 CONNECT 鉴权（常量时间比较） |
| `iotbridge.mqtt.idle-seconds` | 300 | keepalive=0 时的兜底读空闲秒数 |
| `iotbridge.mqtt.connect-timeout-seconds` | 30 | CONNECT 握手读空闲超时（秒）；连上不发 CONNECT 的连接超时断开（slowloris 防护），<=0 关闭 |
| `iotbridge.mqtt.max-connections` | 10000 | 最大并发 TCP 连接数，超限新连接直接关闭，<=0 不限制 |
| `iotbridge.mqtt.write-buffer-high/low-water-mark` | 65536 / 32768 | 出站写缓冲水位 |
| `IOTBRIDGE_MQTT_USERNAME` / `IOTBRIDGE_MQTT_PASSWORD` | 空 | 上述凭据的环境变量入口 |

## 五、协议行为约定

1. **设备身份**：CONNECT 的 `clientId` 即 `deviceId`（最长 128 字符），会话键为 `(mqtt, clientId)`；载荷中内嵌的 `deviceId` 字段会被剥离，防止伪造/双重身份。
2. **上行**：PUBLISH 到 `iotbridge/uplink` 或其子主题；JSON 对象映射为 `payload`，非 JSON 保留 `payload.raw`；QoS1 回 PUBACK，QoS2 断开。
3. **下行**：REST `protocolType=mqtt` → 编码为 `{"command","params","requestId"}` → 发布到 `iotbridge/downlink/<clientId>`；设备未订阅对应主题时写失败，走离线暂存/丢弃策略。
4. **订阅**：支持 `+` / `#` 通配；SUBACK 授予 QoS 上限为 1。
5. **活性**：CONNECT 前按 `connect-timeout-seconds` 做握手空闲检测；CONNECT 后 keepalive 非 0 按 1.5 倍读空闲检测、为 0 用 `idle-seconds`。
6. **互踢**：同一 clientId 重复连接时新连接顶替旧连接（沿用 `ChannelManager` 语义）。
7. **拒绝场景**：协议版本非 3.1/3.1.1、clientId 为空/超长、凭据错误、未 CONNECT 即发报文、并发连接超 `max-connections`。

## 六、测试与验证

| 测试类 | 用例 | 覆盖 |
| --- | --- | --- |
| `MqttServerTest` | 5 | 真实 Netty MQTT 客户端端到端（CONNECT→SUBSCRIBE→PUBLISH 上行→下行 PUBLISH）、错误密码 CONNACK 拒绝、非法 QoS 启动失败、CONNECT 握手超时断开、连接数超限拒绝 |
| `MqttTopicMatcherTest` | 6 | 精确 / `+` / `#` / 非法过滤器 / 订阅集合匹配 |
| `MqttPayloadCodecTest` | 4 | JSON 载荷映射（内嵌 deviceId 剥离）、非 JSON、空载荷、下行 JSON 编码 |
| `ProtocolStartupValidatorTest` | +3 | MQTT 协议缺失即启动失败、关闭时跳过、已注册通过 |

全量 `mvn test`：**81/81 通过**（15 个测试类）。

### 手工验证

```bash
mosquitto_sub -h localhost -p 1883 -i dev-mqtt -t iotbridge/downlink/dev-mqtt
mosquitto_pub -h localhost -p 1883 -i dev-mqtt -t iotbridge/uplink/dev-mqtt \
  -m '{"temperature":26.5,"humidity":60}'
curl -XPOST http://localhost:8080/api/v1/devices/dev-mqtt/commands \
  -H 'Content-Type: application/json' \
  -d '{"protocolType":"mqtt","command":"setInterval","params":{"value":30}}'
```

## 七、升级注意

1. **默认开启**：升级后网关会额外监听 `1883`；若端口被占用将启动失败，可用 `iotbridge.mqtt.enabled=false` 关闭。
2. **新增协议标识**：下行必须显式 `protocolType=mqtt`，否则会按默认协议编码（demo-text）。
3. **设备需订阅下行主题**，否则在线但收不到指令；建议开启 `iotbridge.downlink.buffer-enabled` 兜底。
4. **明文接入**：1883 无 TLS，生产建议前置 TLS 终结（8883）并配置 MQTT 用户名密码。

## 八、仍存在的限制

- 不支持 MQTT 5.0、QoS2、retained 消息、Will 消息；
- **上行 QoS1 为「收到即回 PUBACK」**：解码/投递失败不重投，QoS1 仅保证 TCP 层传输，端到端不构成至少一次投递（如需闭环需实现处理成功后确认 + packetId 缓存）；
- 下行 QoS1 不跟踪客户端重传；
- 会话仍为单实例内存态，多实例需配合一致哈希路由；
- 无 MQTT 指标监控（连接数、吞吐、拒绝次数）。
