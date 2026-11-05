# iotbridge — 全双工 IoT 协议转换网关

> 面向 Git 托管平台的项目介绍。完整说明见 [README](README.md)，协议细节见 [SOS-C02 V3.0 协议规范](docs/protocol-sos-c02.md)，部署运维见 [部署与运维手册](docs/deployment.md)。

**一句话简介**：全双工 IoT 协议转换网关——Netty TCP 接入 + SPI 协议插件化 + Kafka 上行 + RabbitMQ 下行；多协议多监听、按 `(协议, 设备号)` 会话隔离；内置 `demo-text` 与 `sos-c02` 消防设备协议插件。

## 概述

iotbridge 是一个面向海量硬件设备接入的协议转换网关。设备通过 **Netty TCP 长连接**接入，协议编解码以 **SPI 插件**方式扩展；上行数据经 **Kafka** 投递业务平台，平台指令经 **RabbitMQ** 回发设备，实现上下行**全双工转发**与链路解耦。工程为 **Spring Boot 3.2.5 + JDK 17** 的 Maven 多模块结构，遵循分层与向后兼容，接入层与 mq 模块均不反向依赖核心。

- 坐标：`com.iotbridge:iotbridge:1.0.0-SNAPSHOT`
- 语言/框架基线：JDK 17，Spring Boot 3.2.5（`jakarta.*`）

## 核心特性

- **协议插件化**：实现 `ProtocolCodec` SPI + `META-INF/services` 注册，启动自动发现，按 `protocolType` 路由，新增协议无需改动网关主干。
- **多协议多监听**：`iotbridge.netty.listeners[]` 按端口绑定协议与切帧方式，单实例可并行接入文本（`DELIMITER`）与二进制（`LENGTH_FIELD`）协议。
- **会话按 (协议, 设备号) 隔离**：`ChannelManager`、离线缓冲与补发统一以 `SessionKey(protocolType, deviceId)` 复合键寻址，不同协议下的相同设备号各自独立、互不覆盖。
- **下行离线暂存重投（可选）**：设备离线时按会话暂存**原始指令**（有界 + TTL），重新上线后按会话串行、**重新编码**再补发，规避时间戳类指令失效、发送失败退回剩余帧。
- **优雅生命周期**：`GatewayLifecycle`（`SmartLifecycle`）随 Spring 容器自动启停 Netty 服务端。
- **运维接口**：REST 下发指令 + 在线设备查询（支持 `?protocol=` 过滤）+ 聚合/精确在线判定。

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17 | 语言运行时基线 |
| Spring Boot | 3.2.5 | Web / 容器 / 自动装配（`jakarta.*`） |
| Netty | 4.1.100.Final | TCP 长连接接入与帧切分 |
| Spring Kafka | 随 Boot | 上行消息生产 |
| Spring AMQP (RabbitMQ) | 随 Boot | 下行指令收发（JSON 转换） |

## 架构与数据流

```
上行：设备 --TCP--> access-netty(帧切分 + DeviceInboundHandler)
        --UplinkFrameEvent--> bridge-core UplinkDispatcher(SPI 解码、绑定会话)
        --JSON--> mq KafkaUplinkProducer --Kafka--> 业务平台

下行：业务平台 --REST--> admin CommandController
        --JSON--> mq RabbitDownlinkPublisher --RabbitMQ--> DownlinkCommandConsumer
        --DownlinkDispatchHandler--> bridge-core DownlinkDispatcher(SPI 编码)
        --byte[]--> access-netty ChannelManager --TCP--> 设备
```

> 解耦要点：接入层通过 `UplinkFrameListener` 向上回调；mq 通过 spi 的 `DownlinkDispatchHandler` 向下回调，二者均由 `bridge-core` 实现，避免 `mq` / `access-netty` 反向依赖核心。

## 模块结构（自底向上、无循环依赖）

```
iotbridge (parent)
├── iotbridge-common        Result / 错误码 / 异常 / JSON 工具 / 常量
├── iotbridge-spi           协议插件契约：ProtocolCodec + 消息模型 + SessionKey
├── iotbridge-plugin-demo   行文本示例协议（demo-text）
├── iotbridge-plugin-sos    SOS-C02 V3.0 消防设备协议（二进制帧 + CRC16）
├── iotbridge-mq            Kafka 上行生产者 + RabbitMQ 下行收发
├── iotbridge-access-netty  Netty TCP 服务端 + 帧切分 + 设备会话管理
├── iotbridge-bridge-core   协议注册表 + 上/下行分发器 + 网关生命周期
├── iotbridge-admin         下行指令与在线查询 REST API
└── iotbridge-starter       启动装配 + 配置 + 演示客户端与 e2e 脚本
```

## 快速开始

```bash
# 1) 启动中间件（Kafka + RabbitMQ）
docker compose -f docker/docker-compose.yml up -d

# 2) 构建（含测试）
mvn clean package

# 3) 运行（基线已默认 9000=sos-c02、9001=demo-text 多监听）
java -jar iotbridge-starter/target/iotbridge.jar

# 4) 跨协议同号会话隔离端到端验证
./scripts/e2e-session-isolation.sh
```

## 适用场景

燃气 / 烟感 / 温感等 GPRS/4G/WIFI 无线终端（如 SOS-C02 / C03B / W03-A / C03-AO 消防设备）的统一接入、协议归一与上下行转发；尤其适合需要**多协议并存**、**设备级会话隔离**与**离线指令补发**的 IoT 接入层。

## 相关文档

- [README](README.md)：工程总览、配置参考、扩展新协议、测试与限制。
- [SOS-C02 V3.0 协议规范](docs/protocol-sos-c02.md)：帧结构、CRC、命令集、数据体字节级布局、上下行编解码细则。
- [部署与运维手册](docs/deployment.md)：环境、构建、配置、启动、接口调用、故障排查与多实例注意事项。

## Topics / 标签

`iot` `gateway` `netty` `spring-boot` `kafka` `rabbitmq` `spi` `protocol-plugin` `tcp` `crc16` `multi-protocol` `device-session` `java17`
