# iotbridge — 全双工 IoT 协议转换网关

基于 **Spring Boot 3.2.5 + JDK 17** 的 Maven 多模块工程。硬件设备经 **Netty TCP 长连接**接入，协议编解码通过 **SPI 插件化**扩展，上行数据经 **Kafka** 投递业务平台，平台指令经 **RabbitMQ** 回发设备，实现上下行全双工转发。

内置两套协议插件：

- `demo-text`：行文本协议，作为最小可运行示例；
- `sos-c02`：SOS-C02 / C03B / W03-A / C03-AO 消防设备通信协议 V3.0，二进制帧 + CRC16，适用于燃气/烟感/温感等 GPRS/4G/WIFI 无线终端。

## 目录

- [1. 特性](#1-特性)
- [2. 技术栈](#2-技术栈)
- [3. 架构与数据流](#3-架构与数据流)
- [4. 快速开始](#4-快速开始)
- [5. 消息契约](#5-消息契约)
- [6. REST API](#6-rest-api)
- [7. 帧切分模式](#7-帧切分模式)
- [8. 示例协议 demo-text](#8-示例协议-demo-text)
- [9. SOS-C02 V3.0 消防设备协议](#9-sos-c02-v30-消防设备协议)
- [10. 配置参考](#10-配置参考)
- [11. 扩展新协议](#11-扩展新协议)
- [12. 测试](#12-测试)
- [13. 工程结构](#13-工程结构)
- [14. 设计说明与限制](#14-设计说明与限制)

## 相关文档

- [SOS-C02 V3.0 协议规范](docs/protocol-sos-c02.md)：帧结构、CRC、命令集、数据体字节级布局、上下行编解码细则。
- [部署与运维手册](docs/deployment.md)：环境、构建、配置、启动、接口调用、故障排查。

## 1. 特性

- **协议插件化**：实现 `ProtocolCodec` SPI + `META-INF/services` 注册，网关启动自动发现，按 `protocolType` 路由。
- **全双工转发**：上行 TCP→Kafka，下行 REST→RabbitMQ→TCP，链路解耦、可独立伸缩。
- **文本/二进制并存**：Netty 帧切分支持行分隔（`DELIMITER`）与长度字段（`LENGTH_FIELD`）两种模式，可通过配置切换。
- **会话管理**：`ChannelManager` 维护 `deviceId <-> Channel` 双向映射，支持设备上下线清理与按设备寻址下发。
- **离线暂存重投（可选）**：开启后设备离线期间的下行按 `deviceId` 暂存**原始指令**（有界 + TTL），设备重新上线后按设备串行、重新编码再补发（规避时间戳类指令失效、发送失败退回剩余帧），降低离线丢包。
- **优雅生命周期**：`GatewayLifecycle`（`SmartLifecycle`）随 Spring 容器自动启停 Netty 服务端。

## 2. 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17（`maven.compiler.release=17`） | 语言运行时基线 |
| Spring Boot | 3.2.5 | Web / 容器 / 自动装配（`jakarta.*`） |
| Netty | 4.1.100.Final（`netty-bom`） | TCP 长连接接入 |
| Spring Kafka | 随 Boot | 上行消息生产 |
| Spring AMQP (RabbitMQ) | 随 Boot | 下行指令收发（JSON 转换） |

## 3. 架构与数据流

```
上行:  设备 --TCP--> [access-netty 帧切分 + DeviceInboundHandler]
                        --UplinkFrameEvent--> [bridge-core UplinkDispatcher: SPI decode]
                        --JSON--> [mq KafkaUplinkProducer] --Kafka--> 业务平台

下行:  业务平台 --REST--> [admin CommandController]
                        --JSON--> [mq RabbitDownlinkPublisher] --RabbitMQ-->
                        [mq DownlinkCommandConsumer --DownlinkDispatchHandler-->
                         bridge-core DownlinkDispatcher: SPI encode]
                        --byte[]--> [access-netty ChannelManager] --TCP--> 设备
```

模块与依赖（自底向上，杜绝循环依赖）：

```
iotbridge (parent, pom)
├── iotbridge-common        统一响应 Result / 错误码 ErrorCodeEnum / 异常 / JSON 工具 / 常量
├── iotbridge-spi           协议插件契约：ProtocolCodec + UplinkMessage/DownlinkCommand + DownlinkDispatchHandler + FrameRule
├── iotbridge-plugin-demo   示例协议插件（行文本 demo-text），ServiceLoader 注册
├── iotbridge-plugin-sos    SOS-C02/C03B/W03-A/C03-AO 消防设备协议 V3.0（二进制帧 + CRC16）
├── iotbridge-mq            Kafka 上行生产者 + RabbitMQ 下行消费者/发布者
├── iotbridge-access-netty  Netty TCP 服务端 + 帧切分 + 设备会话(Channel)管理
├── iotbridge-bridge-core   协议注册表 + 上/下行分发器 + 网关生命周期
├── iotbridge-admin         下行指令 REST API
└── iotbridge-starter       Spring Boot 启动装配 + 配置 + 演示客户端
```

关键类职责：

| 类 | 模块 | 职责 |
| --- | --- | --- |
| `ProtocolCodec` | spi | 编解码 SPI：`protocolType()` / `decode(byte[])` / `encode(DownlinkCommand)` |
| `NettyTcpServer` | access-netty | 引导 boss/worker，装配帧解码器 + `DeviceInboundHandler` |
| `DeviceInboundHandler` | access-netty | 接收整帧 `ByteBuf`，转字节封装 `UplinkFrameEvent` 回调监听器 |
| `ChannelManager` | access-netty | `deviceId<->Channel` 映射、上下线、`sendToDevice(byte[])` |
| `ProtocolCodecRegistry` | bridge-core | ServiceLoader + Spring Bean 合并索引，`resolve()` / `getDefault()` |
| `UplinkDispatcher` | bridge-core | 实现 `UplinkFrameListener`：默认协议解码 → 绑定会话 → 投递 Kafka |
| `DownlinkDispatcher` | bridge-core | 实现 `DownlinkDispatchHandler`：按协议编码 → 写入设备通道 |
| `GatewayLifecycle` | bridge-core | `SmartLifecycle` 随容器启停 Netty |
| `KafkaUplinkProducer` / `RabbitDownlinkPublisher` | mq | 上行生产 / 下行发布 |
| `CommandController` | admin | 下行指令 REST 入口 |

> 解耦要点：接入层通过 `UplinkFrameListener`（持 Netty `Channel`）向上回调；`mq` 通过 spi 的 `DownlinkDispatchHandler`（仅持 `DownlinkCommand`）向下回调，二者均由 `bridge-core` 实现，避免 `mq`/`access-netty` 反向依赖 core。

## 4. 快速开始

前置：本机需运行 Kafka 与 RabbitMQ（可用 `docker/docker-compose.yml`）。

1. 启动中间件：
   ```bash
   docker compose -f docker/docker-compose.yml up -d
   ```
2. 编译并以默认（demo-text）配置运行网关：
   ```bash
   mvn -q -DskipTests clean package
   mvn -pl iotbridge-starter spring-boot:run
   # 或： java -jar iotbridge-starter/target/iotbridge.jar
   ```
   - HTTP：`8080`，设备 TCP：`9000`
3. 运行模拟设备：在 IDE 直接运行 `com.iotbridge.demo.DemoDeviceClient.main`（位于 `iotbridge-starter` 测试目录），它会连接 `9000` 并发送上行：
   ```
   dev001,temperature=26.5,humidity=60
   ```
4. 平台下发指令：
   ```bash
   curl -XPOST http://localhost:8080/api/v1/devices/dev001/commands \
        -H 'Content-Type: application/json' \
        -d '{"command":"setInterval","params":{"value":30}}'
   ```
   模拟设备端将打印收到的下行报文：`dev001:setInterval:value=30`

> 使用 `sos-c02` 二进制协议请见 [第 9 节](#9-sos-c02-v30-消防设备协议)。

## 5. 消息契约

### 5.1 上行（Kafka）

`KafkaUplinkProducer` 以 `deviceId` 为分区 key，将 `UplinkMessage` 序列化为 JSON 投递到 `iotbridge.uplink`：

```json
{
  "deviceId": "dev001",
  "protocolType": "demo-text",
  "payload": { "temperature": "26.5", "humidity": "60" },
  "timestamp": 1762310400000
}
```

### 5.2 下行（RabbitMQ）

`RabbitDownlinkPublisher` 经直连交换机 `iotbridge.downlink.exchange`（路由键 `downlink`）投递 `DownlinkCommand`（JSON）到持久队列 `iotbridge.downlink`：

```json
{
  "deviceId": "dev001",
  "protocolType": "demo-text",
  "command": "setInterval",
  "params": { "value": 30 },
  "requestId": "0b1c2d3e-...."
}
```

`DownlinkCommandConsumer` 监听队列并回调 `DownlinkDispatchHandler.handle(command)`。

> 注意：上行解码始终使用 `iotbridge.protocol.default` 指定的默认协议；`protocolType` 仅对**下行**指令生效（用于在同实例内多协议路由）。

### 5.3 统一响应与错误码

REST 统一返回 `Result<T>`：`{ "success": bool, "code": string, "message": string, "data": ... }`。

| 错误码 | 含义 |
| --- | --- |
| `0` | 成功（SUCCESS） |
| `A0400` | 请求参数非法（BAD_REQUEST） |
| `B0001` | 系统内部错误（SYSTEM_ERROR） |
| `B0100` | 未找到对应协议插件（PROTOCOL_NOT_FOUND） |
| `B0101` | 协议解码失败（DECODE_ERROR） |
| `B0102` | 协议编码失败（ENCODE_ERROR） |
| `B0200` | 设备离线（DEVICE_OFFLINE） |

## 6. REST API

### 6.1 下发指令

**POST** `/api/v1/devices/{deviceId}/commands`

请求体（`CommandRequest`）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `command` | string | 是 | 指令名称 |
| `params` | object | 否 | 指令参数（默认空对象） |
| `protocolType` | string | 否 | 目标协议；为空回退默认协议 |

成功响应（`data` 为生成的 `requestId`，用于链路追踪）：

```json
{ "success": true, "code": "0", "message": "成功", "data": "0b1c2d3e-...." }
```

### 6.2 在线设备查询

用于排查“设备是否在线”（下行未送达通常因设备离线）：

**GET** `/api/v1/devices/online` — 列出当前在线设备（含远端地址）：

```bash
curl http://localhost:8080/api/v1/devices/online
```
```json
{ "success": true, "code": "0", "message": "成功",
  "data": [ { "deviceId": "869975034441082", "remoteAddress": "/127.0.0.1:52000" } ] }
```

**GET** `/api/v1/devices/{deviceId}/online` — 判断单个设备是否在线：

```bash
curl http://localhost:8080/api/v1/devices/869975034441082/online
```
```json
{ "success": true, "code": "0", "message": "成功", "data": true }
```

> 在线状态基于当前网关实例内存会话（`ChannelManager`）；设备需成功处理过至少一帧上行才会登记，多实例部署时不共享。

## 7. 帧切分模式

`NettyTcpServer` 依据 `iotbridge.netty.frame-mode` 装配流水线首端的帧解码器：

| 模式 | 解码器 | 适用 |
| --- | --- | --- |
| `DELIMITER`（默认） | `DelimiterBasedFrameDecoder(maxFrameLength, \n)` | 行文本协议（demo-text） |
| `LENGTH_FIELD` | `LengthFieldBasedFrameDecoder(maxFrameLength, offset, length, adjustment, strip)` | 二进制协议（SOS V3.0） |

两种模式下帧解码器均输出整帧 `ByteBuf`，由 `DeviceInboundHandler` 转为 `byte[]` 封装 `UplinkFrameEvent(channel, frame, remote)` 交给 `UplinkDispatcher`，再由协议插件 `decode`。因此同一套处理逻辑可同时承载文本与二进制协议——只需切换帧模式并指定默认协议。

## 8. 示例协议 demo-text

`DemoTextCodec`（`iotbridge-plugin-demo`，协议标识 `demo-text`）：

- **上行**：`deviceId,key=value,key=value`（行结束符 `\n`）；无值键保留为空串。
- **下行**：`deviceId:command:key=value&key2=value2`（无参数时 `deviceId:command`），自动追加 `\n`。

## 9. SOS-C02 V3.0 消防设备协议

`SosProtocolCodec`（`iotbridge-plugin-sos`，协议标识 `sos-c02`）实现《SOS-C02 SOS-C03B SOS-W03-A SOS-C03-AO 通信协议 V3.0》，适用于燃气/烟感/温感等智能无线终端，基于 TCP，适用 GPRS/4G/WIFI。

> 字节级完整规范（各命令数据体布局、设备/事件类型全表、帧构造示例）见 [docs/protocol-sos-c02.md](docs/protocol-sos-c02.md)。

### 9.1 帧结构（大端）

| 字段 | 字节数 | 偏移 | 说明 |
| --- | --- | --- | --- |
| 帧头 | 3 | 0–2 | 固定 `57 54 4B`（ASCII "WTK"） |
| 协议版本 | 1 | 3 | V3.0 = `0x30` |
| 厂商标识 | 2 | 4–5 | 由厂商分配 |
| 数据序列号 | 2 | 6–7 | 帧序号 |
| 命令 | 1 | 8 | 见命令集 |
| 数据长度 N | 2 | 9–10 | 数据体字节数 |
| 数据体 | N | 11–(10+N) | 业务数据 |
| CRC16 | 2 | (11+N)–(12+N) | 校验 |
| 帧尾 | 3 | (13+N)–(15+N) | 固定 `45 4E 44`（ASCII "END"） |

固定开销 = `3+1+2+2+1+2+2+3 = 16` 字节，**整帧长度 = 16 + N**。

### 9.2 校验

CRC16-XMODEM（多项式 `0x1021`，初值 `0`），校验范围 = 帧头 .. 数据体（偏移 `[0, 11+N)`，不含 CRC 与帧尾），实现见 `Crc16Xmodem`。标准测试向量 `"123456789"` → `0x31C3`。

### 9.3 长度字段切帧

`LengthFieldBasedFrameDecoder(maxFrameLength, lengthFieldOffset=9, lengthFieldLength=2, lengthAdjustment=5, initialBytesToStrip=0)`：

- 偏移 9 处的 2 字节即数据长度 N；
- Netty 计算整帧 = 已读(9+2) + (N + adjustment) → 需把数据体之后的 CRC(2)+帧尾(3)=5 计入，故 `adjustment=5`；
- `strip=0` 向下游传递完整帧，供 `SosFrame.parse` 校验帧头/帧尾/长度/CRC。

### 9.4 命令集

| 命令码 | 方向 | 含义 |
| --- | --- | --- |
| `0x01` | 上行 | 设备注册 |
| `0x02` | 上行 | 设备事件上报 |
| `0x03` | 下行 | 指令下发 |
| `0x04` | 上行 | 下发指令回复 |
| `0x05` | 上行 | 长连接（心跳）包 |
| `0x06` | 上行 | 数据上报（含位置等） |
| `0x07`–`0x0A` | — | OTA 升级请求/信息下发/状态上报/取消 |
| `0x0B` | 下行 | 时间校准 |
| `0xFF` | 下行 | 服务器回复 |

服务器回复标识：`0x00` 成功、`0x01` CRC 失败、`0x02` 解析失败、`0x03` 厂商非法。

### 9.5 上行解码字段

所有上行 `payload` 均含：`command`/`commandHex`/`commandName`、`version`、`vendor`（hex）、`sequence`。按命令追加：

| 命令 | 数据体布局 | 追加字段 |
| --- | --- | --- |
| `0x01` 注册 | 类型(1)+IMEI(15)+IMSI(15)+ICCID(20)+电压(1)+CSQ(1)+电量(1)+版本(1) | `deviceType`,`imei`,`imsi`,`iccid`,`batteryVoltage`,`csq`,`batteryPercent`,`programVersion` |
| `0x02` 事件 | 类型(1)+IMEI(15)+事件类型(1)+电压(1)+CSQ(1)+电量(1)+版本(1) | `deviceType`,`imei`,`eventType`,`eventName`,`batteryVoltage`,`csq`,`batteryPercent`,`programVersion` |
| `0x05` 心跳 | IMEI(15) | `imei` |
| `0x06` 数据上报 | 类型(1)+IMEI(15)+数据类型(1)+内容(ASCII) | `deviceType`,`imei`,`dataType`,（内容为 JSON 时解析为 `location`，否则 `content`） |
| 其他 | — | `rawHex` |

派生规则：`batteryVoltage = 电压字节/10`（保留 1 位小数，如 `0x25`→`3.7`）；`programVersion` 高 nibble.低 nibble（`0x10`→`1.0`）；`deviceId` 取数据体 IMEI（心跳取偏移 0，注册/事件/数据上报取偏移 1）。

注册包解码示例 `payload`：

```json
{
  "command": 1, "commandHex": "01", "commandName": "设备注册",
  "version": 48, "vendor": "0001", "sequence": 1,
  "deviceType": "手报(紧急按钮)", "imei": "869975034441082",
  "imsi": "460113118743732", "iccid": "89861120224014398762",
  "batteryVoltage": "3.7", "csq": 21, "batteryPercent": 100, "programVersion": "1.0"
}
```

### 9.6 下行编码

`encode(DownlinkCommand)`：公共参数 `params.vendor`（2 字节 hex，默认 `0000`）、`params.seq`（默认 0）。`command` 名称→命令字节及数据体：

| `command` | 命令字节 | 数据体 | 相关 `params` |
| --- | --- | --- | --- |
| `ack` / `serverReply` / `reply` | `0xFF` | 1 字节回复标识 | `replyFlag`（默认 `0`） |
| `timeCalib` / `time` | `0x0B` | 4 字节大端 epoch 秒 | `timestamp`（缺省取当前秒） |
| `cmd` / `down` / `command` | `0x03` | 自定义 | `dataHex`（十六进制）或 `data`（ASCII） |
| 其他（如 `03` / `0x03`） | 按 1 字节 hex | 空 | — |

### 9.7 运行 SOS 示例

```bash
# 1) 启动中间件 + 以 sos profile 启动网关（LENGTH_FIELD 切帧，默认协议 sos-c02）
docker compose -f docker/docker-compose.yml up -d
mvn -pl iotbridge-starter spring-boot:run -Dspring-boot.run.profiles=sos
# 或： java -jar iotbridge-starter/target/iotbridge.jar --spring.profiles.active=sos

# 2) 运行模拟设备：在 IDE 执行 com.iotbridge.demo.SosDemoClient.main
#    （连接 9000，依次发送注册帧 0x01 与报警事件帧 0x02，并打印下行二进制帧）

# 3) 平台下发时间校准，设备端将收到 0x0B 下行帧：
curl -XPOST http://localhost:8080/api/v1/devices/869975034441082/commands \
     -H 'Content-Type: application/json' \
     -d '{"protocolType":"sos-c02","command":"timeCalib","params":{"vendor":"0001"}}'
```

> `application-sos.yml` 设置 `iotbridge.protocol.default=sos-c02` 且 `frame-mode=LENGTH_FIELD`（`max-frame-length=1024`），上行即按 SOS 解码。

## 10. 配置参考

| 配置 | 说明 | 默认 |
| --- | --- | --- |
| `server.port` | HTTP 端口 | 8080 |
| `iotbridge.protocol.default` | 默认协议类型（上行解码依据；下行可被 `protocolType` 覆盖） | demo-text |
| `iotbridge.netty.host` / `port` | 设备 TCP 监听地址/端口 | 0.0.0.0 / 9000 |
| `iotbridge.netty.boss-threads` / `worker-threads` | boss/worker 线程数（worker=0 依 CPU 核数） | 1 / 0 |
| `iotbridge.netty.max-frame-length` | 单帧最大长度 | 65536 |
| `iotbridge.netty.frame-mode` | 帧切分：`DELIMITER` 文本行 / `LENGTH_FIELD` 二进制 | DELIMITER |
| `iotbridge.netty.length-field-offset` | 长度字段偏移（SOS=9） | 9 |
| `iotbridge.netty.length-field-length` | 长度字段字节数（SOS=2） | 2 |
| `iotbridge.netty.length-adjustment` | 长度修正值（SOS=CRC2+帧尾3=5） | 5 |
| `iotbridge.netty.initial-bytes-to-strip` | 输出前剥离字节（0=保留整帧） | 0 |
| `iotbridge.mq.uplink-topic` | 上行 Kafka 主题 | iotbridge.uplink |
| `iotbridge.mq.downlink-queue` | 下行 RabbitMQ 队列 | iotbridge.downlink |
| `iotbridge.mq.downlink-exchange` | 下行交换机 | iotbridge.downlink.exchange |
| `iotbridge.mq.downlink-routing-key` | 下行路由键 | downlink |
| `iotbridge.downlink.buffer-enabled` | 设备离线时是否暂存待重投 | false |
| `iotbridge.downlink.max-pending-per-device` | 单设备最大暂存指令数（超限丢最旧） | 100 |
| `iotbridge.downlink.max-pending-devices` | 同时暂存的最大设备数（超限淘汰最陈旧设备队列） | 10000 |
| `iotbridge.downlink.pending-ttl-ms` | 暂存指令存活毫秒（`<=0` 不过期） | 300000 |
| `spring.kafka.bootstrap-servers` | Kafka 地址 | localhost:9092 |
| `spring.rabbitmq.host` / `port` / `username` / `password` | RabbitMQ 连接 | localhost:5672 / guest / guest |

## 11. 扩展新协议

1. 新建模块依赖 `iotbridge-spi`，实现 `com.iotbridge.spi.codec.ProtocolCodec`：

   ```java
   public class MyCodec implements ProtocolCodec {
       @Override public String protocolType() { return "my-proto"; }
       @Override public UplinkMessage decode(byte[] frame) { /* 帧 -> UplinkMessage */ }
       @Override public byte[] encode(DownlinkCommand command) { /* 指令 -> 字节帧 */ }
   }
   ```

2. 新增 `src/main/resources/META-INF/services/com.iotbridge.spi.codec.ProtocolCodec`，写入实现类全限定名。
3. 将该模块以 `compile`/`runtime` 依赖加入 `iotbridge-starter`，或作为外部 jar 放入 classpath。
4. 网关启动时 `ProtocolCodecRegistry` 经 `ServiceLoader` 自动发现并按 `protocolType()` 建索引；按需设置 `iotbridge.protocol.default`（决定上行解码协议），下行可按 `protocolType` 精确路由。
5. 若为二进制/非行分隔协议，配套调整 `iotbridge.netty.frame-mode` 及长度字段参数。

## 12. 测试

```bash
mvn test
```

共 **19** 个用例，全部通过：

| 模块 | 测试类 | 数量 | 覆盖 |
| --- | --- | --- | --- |
| plugin-demo | `DemoTextCodecTest` | 3 | 编解码往返、ServiceLoader 发现 |
| plugin-sos | `Crc16XmodemTest` | 2 | CRC16-XMODEM 标准向量 |
| plugin-sos | `SosProtocolCodecTest` | 8 | 各命令解码、编码回解析自洽、篡改 CRC 必失败、ServiceLoader 发现 |
| bridge-core | `ProtocolCodecRegistryTest` | 3 | 插件加载与回退解析 |
| bridge-core | `ChannelManagerTest` | 3 | 会话注册/覆盖/下线 |

## 13. 工程结构

```
iot-bridge/
├── pom.xml                      # 父工程：版本基线 + dependencyManagement(netty-bom/内部模块)
├── README.md
├── docker/docker-compose.yml    # Kafka + RabbitMQ 本地编排
├── iotbridge-common/
├── iotbridge-spi/
├── iotbridge-plugin-demo/
├── iotbridge-plugin-sos/
│   └── .../sos/{SosProtocolCodec, model/SosFrame, protocol/{Crc16Xmodem,HexUtil,SosProtocolConstants}}
├── iotbridge-mq/
├── iotbridge-access-netty/
├── iotbridge-bridge-core/
├── iotbridge-admin/
└── iotbridge-starter/           # 启动装配 + application.yml / application-sos.yml + 演示客户端
```

## 14. 设计说明与限制

- **上行单协议**：上行解码固定使用默认协议；同实例多协议混跑需按协议分端口，或在网关前做协议识别（当前未实现）。
- **下行协议路由**：下行按 `DownlinkCommand.protocolType` 经 `resolve()` 选择插件，为空回退默认。
- **在线判定即写即发（可选缓冲重投）**：设备离线时默认丢弃并告警；开启 `iotbridge.downlink.buffer-enabled` 后按 `deviceId` 暂存**原始下行指令**（有界 + TTL），设备重新上线（收到上行）时由 `DownlinkReflowService` **按设备串行、以当前指令重新编码后**按序补发（规避 `timeCalib` 等含时间戳指令在离线期间失效）；补发中发送失败则中断并把剩余帧按原序退回缓冲，等待下次上线，杜绝静默丢失。缓冲仅存于当前实例内存，进程重启清空、多实例不共享。
- **未包含**：设备鉴权、指令幂等与应答确认闭环、设备影子持久化、集群化会话共享、指标监控等——接口位已预留，可迭代增强。
- **SOS 样例帧**：因协议文档样例中厂商码/CRC 为占位，测试采用“自造合法帧 + 回解析自洽 + 篡改 CRC 必失败”策略；如有含真实值的完整样例帧，可补充逐字节比对用例。
