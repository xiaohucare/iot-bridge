# 贡献指南

感谢参与 iotbridge 的开发！本文说明开发环境、构建与测试、模块分层与依赖约束、代码规范以及提交/PR 流程。

## 1. 开发环境

- **JDK 17**（`maven.compiler.release=17`）
- **Maven 3.8+**
- **Docker**（本地联调需要 Kafka 与 RabbitMQ）

启动中间件：

```bash
docker compose -f docker/docker-compose.yml up -d
```

## 2. 构建与测试

```bash
# 全量构建并运行测试
mvn clean package

# 仅构建、跳过测试
mvn -q -DskipTests package

# 运行网关（基线默认多监听：9000=sos-c02、9001=demo-text）
java -jar iotbridge-starter/target/iotbridge.jar

# 跨协议同号会话隔离端到端验证（脚本自带独立端口与专用下行队列，结束自动清理）
./scripts/e2e-session-isolation.sh
```

演示客户端位于 `iotbridge-starter` 测试目录，可接收启动参数覆盖设备号/主机/端口：

- `com.iotbridge.demo.SosDemoClient`（SOS 二进制，默认端口 9000）
- `com.iotbridge.demo.DemoDeviceClient`（demo-text 行文本，默认端口 9001）

## 3. 模块分层与依赖约束

自底向上组织，**严禁循环依赖**：

```
common
  └─ spi
       ├─ plugin-demo / plugin-sos
       ├─ mq
       ├─ access-netty        （传输基础设施：会话管理 ChannelManager）
       │     └─ access-mqtt   （内部 MQTT 服务端，复用 ChannelManager）
       └─ bridge-core         （依赖 spi + mq + access-netty）
             └─ admin         （依赖 common + spi + mq）
                  └─ starter  （聚合装配）
```

关键约束（改动前务必遵守）：

- `access-netty` / `access-mqtt` / `mq` **不得反向依赖** `bridge-core`、`admin`。
- 上行由接入层回调 `UplinkFrameListener` / `UplinkFrameEvent`（位于 `access-netty` 的 `api` 包，因需持有 Netty `Channel` 而不放入 `spi`）；
  下行由 `mq` 通过 `spi` 的 `DownlinkDispatchHandler` 向下回调；上行/下行投递的中间件契约 `UplinkProducer` / `DownlinkPublisher` 位于 `mq.api`。
- 传输无关的对外契约统一放在 `spi`：编解码 `ProtocolCodec`、消息模型、会话查询 `DeviceSessionQuery` / `DeviceSessionView` / `SessionKey`、投递结果 `DeliveryResult`。
- **已知待改点**：`bridge-core` 目前直接依赖 `access-netty` 的 `ChannelManager`（Netty `Channel` 语义），新增加第三种接入方式前应把「会话注册 + 下行写入」抽象上移到 `spi`（见 `docs/design-review-2026-11-14.md` P1-8），不要在新模块里再复制一份 `ChannelManager`。
- 新增协议：实现 `ProtocolCodec` 并在 `META-INF/services` 注册，无需改动网关主干。

## 4. 代码规范

- 遵循**阿里巴巴 Java 开发手册**：命名（UpperCamelCase / lowerCamelCase / UPPER_SNAKE_CASE）、控制语句强制大括号、`if/for/while` 空格、SLF4J `{}` 占位日志、空集合代替 `null`、`BigDecimal` 用于金额、`try-with-resources` 释放资源等。
- **注释使用中文**；公共类与公共/受保护方法需 Javadoc（类含作者/日期，方法含 `@param`/`@return`/`@throws`）。
- 分层与向后兼容：改动契约时保留既有重载（如 `isOnline(deviceId)` 聚合判定与 `isOnline(protocolType, deviceId)` 精确判定并存），避免破坏调用方。
- 日志不输出敏感信息（密码、令牌、个人信息）；昂贵的 `DEBUG` 日志用 `log.isDebugEnabled()` 包裹。

## 5. 配置约定

- 配置前缀 `iotbridge.*`，见 `iotbridge-starter/src/main/resources/application.yml` 与 README《配置参考》。
- **Spring 集合绑定不跨属性源合并**：命令行/多源覆盖 `iotbridge.netty.listeners[...]` 会整体替换列表，需在同一配置源完整声明（`e2e-session-isolation.sh` 即以此方式提供专用监听与独立下行队列）。

## 6. 测试要求

- 每个行为变更配套单元/集成测试；协议编解码补充回解析自洽与篡改校验用例。
- 涉及会话/下行寻址的改动，需覆盖「相同设备号、不同协议互不覆盖」的隔离场景。
- 涉及下行投递的改动，需覆盖三态语义：`UNKNOWN`（写等待超时）**不得**进入离线暂存/重投；`NOT_DELIVERED` 才允许暂存；同一 `requestId` 已送达后重复投递必须被幂等跳过。
- 涉及缓存/队列的改动，必须给出容量上限与超限行为（拒绝并计数），不得引入无界队列。
- 提交前确保 `mvn clean package` 全绿。

## 7. 提交与 PR 流程

1. 从 `main` 切出特性分支：`feat/<简述>`、`fix/<简述>`、`docs/<简述>`。
2. 提交信息建议采用约定式提交：`feat: ...`、`fix: ...`、`docs: ...`、`refactor: ...`、`test: ...`、`chore: ...`。
3. 一次 PR 聚焦一件事；描述动机、方案与影响面，关联相关配置/文档变更。
4. 破坏性变更需在 `CHANGELOG.md` 的 `[Unreleased]` 记录并说明迁移方式。
5. 等待构建通过与评审后再合并。

## 8. 变更日志

重要变更请同步写入根目录 [`CHANGELOG.md`](CHANGELOG.md)（Keep a Changelog + 语义化版本）。

## 9. 许可

在提交贡献即表示同意你的贡献遵循本项目的 [MIT License](LICENSE)。
