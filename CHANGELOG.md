# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

_开发中的特性会先归集于此。_

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
