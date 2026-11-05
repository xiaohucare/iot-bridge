# iotbridge — 全双工 IoT 协议转换网关

基于 **Spring Boot 3.2 + JDK 17** 的 Maven 多模块工程。硬件设备经 **Netty TCP 长连接**接入，协议编解码通过 **SPI 插件化**，上行数据经 **Kafka** 投递业务平台，平台指令经 **RabbitMQ** 回发设备，实现上下行全双工转发。

## 架构

数据流：

```
上行:  设备 --TCP--> access-netty --(SPI decode)--> bridge-core --Kafka--> 业务平台
下行:  业务平台 --REST--> admin --RabbitMQ--> bridge-core --(SPI encode)--> access-netty --TCP--> 设备
```

模块与依赖（自底向上，杜绝循环依赖）：

```
iotbridge (parent, pom)
├── iotbridge-common        通用常量 / 统一响应 / 异常 / JSON 工具
├── iotbridge-spi           协议插件契约：ProtocolCodec + 上/下行模型 + 帧规则 + 回调接口
├── iotbridge-plugin-demo   示例协议插件（行文本），ServiceLoader 注册
├── iotbridge-plugin-sos    SOS-C02/C03B/W03-A/C03-AO 消防设备协议 V3.0（二进制帧 + CRC16）
├── iotbridge-mq            Kafka 上行生产者 + RabbitMQ 下行消费者/发布者
├── iotbridge-access-netty  Netty TCP 服务端 + 设备会话(Channel)管理
├── iotbridge-bridge-core   协议注册 / 上下行分发 / 网关生命周期
├── iotbridge-admin         下行指令 REST API
└── iotbridge-starter       Spring Boot 启动装配 + 配置
```

## 快速开始

前置：本机需运行 Kafka 与 RabbitMQ（可用 `docker/docker-compose.yml`）。

1. 启动中间件：
   ```bash
   docker compose -f docker/docker-compose.yml up -d
   ```
2. 编译并运行网关：
   ```bash
   mvn -q -DskipTests clean package
   mvn -pl iotbridge-starter spring-boot:run
   # 或： java -jar iotbridge-starter/target/iotbridge.jar
   ```
   - HTTP：`8080`，Netty TCP：`9000`
3. 运行模拟设备（IDE 直接跑 `DemoDeviceClient.main`，需先把 test 类加入运行 classpath）：
   - 发送上行：`dev001,temperature=26.5,humidity=60`
4. 平台下发指令：
   ```bash
   curl -XPOST http://localhost:8080/api/v1/devices/dev001/commands \
        -H 'Content-Type: application/json' \
        -d '{"command":"setInterval","params":{"value":30}}'
   ```
   模拟设备将收到：`dev001:setInterval:value=30`

## 示例协议（demo-text）

- 上行：`deviceId,key=value,key=value`
- 下行：`deviceId:command:key=value&key2=value2`（无参数时 `deviceId:command`）
- 行结束符：`\n`

## SOS-C02 V3.0 消防设备协议（iotbridge-plugin-sos）

适用于燃气/烟感/温感等智能无线终端（GPRS/4G/WIFI，TCP 长连接）的二进制协议插件。帧结构（大端十六进制）：

```
帧头(3)  版本(1) 厂商标识(2) 数据序列号(2) 命令(1) 数据长度(2) 数据体(N) CRC16(2) 帧尾(3)
57 54 4B  0x30    vendor      seq          cmd     N          data      crc      45 4E 44
```

- 命令集：`0x01` 注册、`0x02` 事件上报、`0x05` 长连接、`0x06` 数据上报、`0x0B` 时间校准、`0xFF` 服务器回复等（见 `SosProtocolConstants`）。
- 校验：CRC16-XMODEM（多项式 `0x1021`，初值 `0`），校验范围 = 帧头..数据体，由 `Crc16Xmodem` 实现。
- 解码：按命令拆解 IMEI/设备类型/事件类型/电压/CSQ/电量/程序版本；数据上报的位置 JSON 解析进 `payload.location`。
- 编码下行：`command` 支持 `ack`/`timeCalib`/`cmd` 或直接十六进制；参数 `vendor`(2字节hex)、`seq`、`timestamp`、`replyFlag`、`dataHex`。

二进制帧按长度字段切帧，需用 `LENGTH_FIELD` 模式（Netty `LengthFieldBasedFrameDecoder(1024, offset=9, len=2, adjustment=5, strip=0)`）：

```bash
# 1) 启动中间件 + 以 sos profile 启动网关
docker compose -f docker/docker-compose.yml up -d
mvn -pl iotbridge-starter spring-boot:run -Dspring-boot.run.profiles=sos
# 2) 运行模拟设备：IDE 执行 com.iotbridge.demo.SosDemoClient.main（连接 9000，发注册帧+报警事件帧）
# 3) 平台下发（时间校准），设备端将收到二进制下行帧：
curl -XPOST http://localhost:8080/api/v1/devices/869975034441082/commands \
     -H 'Content-Type: application/json' \
     -d '{"protocolType":"sos-c02","command":"timeCalib","params":{"vendor":"0001"}}'
```

> `iotbridge.protocol.default=sos-c02` 时上行按 SOS 解码；也可在指令/上报中显式携带 `protocolType` 以在同实例区分多协议。

## 扩展新协议

1. 新建模块依赖 `iotbridge-spi`，实现 `com.iotbridge.spi.codec.ProtocolCodec`。
2. 在 `src/main/resources/META-INF/services/com.iotbridge.spi.codec.ProtocolCodec` 写入实现类全限定名。
3. 将该模块以 `runtime` 依赖加入 `iotbridge-starter`，或作为外部 jar 放入 classpath。
4. 网关启动时 `ProtocolCodecRegistry` 自动发现并按 `protocolType()` 路由；可在 `iotbridge.protocol.default` 指定默认协议。

## 配置项

| 配置 | 说明 | 默认 |
| --- | --- | --- |
| `server.port` | HTTP 端口 | 8080 |
| `iotbridge.netty.port` | 设备 TCP 接入端口 | 9000 |
| `iotbridge.netty.frame-mode` | 帧切分：`DELIMITER` 文本行 / `LENGTH_FIELD` 二进制 | DELIMITER |
| `iotbridge.netty.length-field-*` / `length-adjustment` / `initial-bytes-to-strip` | LENGTH_FIELD 切帧参数 | 9 / 2 / 5 / 0 |
| `iotbridge.protocol.default` | 默认协议类型 | demo-text |
| `iotbridge.mq.uplink-topic` | 上行 Kafka 主题 | iotbridge.uplink |
| `iotbridge.mq.downlink-queue` | 下行 RabbitMQ 队列 | iotbridge.downlink |
| `spring.kafka.bootstrap-servers` / `spring.rabbitmq.*` | 中间件地址 | localhost |

## 测试

```bash
mvn -q test
```

- `iotbridge-plugin-demo`：`DemoTextCodecTest` 编解码往返 + ServiceLoader 发现
- `iotbridge-bridge-core`：`ChannelManagerTest` 会话注册/下线；`ProtocolCodecRegistryTest` 插件加载与回退

## 说明与后续

- 骨架聚焦清晰扩展点，未包含鉴权、设备影子持久化、集群化会话、下行 per-device 队列等，接口位已预留，可迭代增强。
