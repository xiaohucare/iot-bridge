# iotbridge 文档索引

全部文档的入口。README 是「一本通」，本页负责按**读者目的**分流，避免在 600 行里找答案。

## 想先跑起来

| 文档 | 内容 |
| --- | --- |
| [README · 快速开始](../README.md#4-快速开始) | 依赖、构建、单机启动、发第一帧、看到第一行日志 |
| [README · 示例协议 demo-text](../README.md#8-示例协议-demo-text) | 最小可运行的行文本协议，30 分钟内可跑通上下行 |
| [docker/](../docker/) | `docker compose` 编排与本地依赖（Kafka、RabbitMQ） |

## 想部署上线

| 文档 | 内容 |
| --- | --- |
| [部署与运维手册](deployment.md) | 环境与 JDK、构建打包、全部配置项、启动方式、接口调用、**生产安全基线**、设备接入认证、多实例、MQTT、日志、健康检查与指标、故障排查表 |
| [监控指标目录](monitoring/metrics.md) | 8 个核心指标的语义、标签取值、PromQL 示例、告警建议、基数与多实例边界 |
| [CHANGELOG](../CHANGELOG.md) | 每次变更的 Added / Changed / Fixed / Docs |

部署前至少要读 `deployment.md` 的 §4.4「生产安全基线」：`prod` 下 api-key 为空、MQTT 启用却无凭据、设备认证未强制，都会**直接拒绝启动**。

## 想接新协议

| 文档 | 内容 |
| --- | --- |
| [README · 扩展新协议](../README.md#11-扩展新协议) | `ProtocolCodec` SPI 契约、插件注册方式、编解码职责边界 |
| [CONTRIBUTING](../CONTRIBUTING.md) | 模块分层与依赖方向、契约放置位置、测试要求、提交规范 |
| [SOS-C02 V3.0 协议规范](protocol-sos-c02.md) | 二进制帧结构、CRC16、命令集、数据体字节级布局、上下行编解码细则（新协议可照此写文档） |

## 想了解设计与已知不足

| 文档 | 内容 |
| --- | --- |
| [设计评审（2026-11-14）](design-review-2026-11-14.md) | 全仓评审：P0/P1/P2 分级问题、文档缺口 D1–D12、三波推进顺序；§7 与 §8 记录已落地项 |
| [README · 设计说明与限制](../README.md#14-设计说明与限制) | 当前实现的边界：单实例会话、明文链路、无应答闭环、MQTT 能力范围 |

## 发行版本

| 文档 | 内容 |
| --- | --- |
| [v0.1.0 发行说明](release-notes-v0.1.0.md) | 首个可用版本：能力清单、升级注意、已知限制 |

## 历史变更记录

| 文档 | 内容 |
| --- | --- |
| [2026-11-06 变更说明](change-summary-2026-11-06.md) | MQTT 接入与多协议监听落地过程 |
| [2026-11-06 MQTT 变更说明](change-summary-mqtt-2026-11-06.md) | MQTT 相关改动细节 |

## 文档维护约定

- 新增配置项：同步更新 `README.md` §10 配置参考与 `deployment.md` 对应小节。
- 新增指标：同步 `monitoring/metrics.md`（含 PromQL 与告警）。
- 行为语义变更：同步 `CHANGELOG.md`，并在 `design-review-2026-11-14.md` 对应条目下记录落地状态。
- 新增协议插件：必须附一份与 `protocol-sos-c02.md` 同粒度的协议规范。
