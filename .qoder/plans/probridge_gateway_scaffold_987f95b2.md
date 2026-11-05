# iotbridge 协议转换网关 — 多模块工程骨架

## 1. 总览
- 父工程 `iotbridge`（`packaging=pom`），以 `spring-boot-starter-parent:3.2.5` 为 parent，统一 `dependencyManagement`。
- 8 个子模块，职责单一、依赖自底向上，杜绝循环依赖。
- 上行：设备 TCP -> access-netty -> bridge-core -> mq(Kafka) -> 业务平台。
- 下行：业务平台 REST -> admin -> mq(RabbitMQ) -> bridge-core -> access-netty -> 设备 TCP。
- 协议编解码通过 SPI（`java.util.ServiceLoader` + `META-INF/services`）插件化，`iotbridge-plugin-demo` 给出可运行实现。
- 全程遵循阿里巴巴 Java 规范：4 空格缩进、K&R 大括号、SLF4J `{}` 占位、包装类型 POJO、空集合代替 null、中文注释。

## 2. 技术基线与版本
- JDK 17；命名空间 `jakarta.*`（Spring Boot 3）。
- Spring Boot 3.2.5（managed）；`spring-kafka` 3.1.x；`spring-boot-starter-amqp`。
- Netty 4.1.x：引入 `io.netty:netty-bom` 统一版本，使用 `netty-all`（或 `netty-handler`+`netty-codec`+`netty-transport`）。
- 其他：`lombok`（谨慎用于 DTO）、`jackson`（Boot 管理）。

## 3. 模块与依赖关系
```
iotbridge/ (parent, pom)
├── iotbridge-common          依赖: 无 (jackson/lombok/slf4j-api)
├── iotbridge-spi             依赖: common
├── iotbridge-plugin-demo     依赖: spi            (SPI 实现 + META-INF/services)
├── iotbridge-mq              依赖: common, spi    (Kafka 上行 + RabbitMQ 下行)
├── iotbridge-access-netty    依赖: common, spi     (Netty 服务端 + 会话/Channel 管理)
├── iotbridge-bridge-core     依赖: common, spi, mq, access-netty, plugin-demo(runtime)
├── iotbridge-admin           依赖: common, mq      (下行指令 REST API)
└── iotbridge-starter         依赖: 以上全部         (Spring Boot main + application.yml + 装配)
```
- `groupId=com.iotbridge`，`artifactId` 同上，包根 `com.iotbridge.<module>`。
- 父 pom 集中声明 `<modules>`、版本属性、`dependencyManagement`（含 netty-bom import）。

## 4. 各模块职责与关键文件

### iotbridge-common
- `com.iotbridge.common.constant.IotbridgeConstants`：主题/队列名、分隔符等常量。
- `com.iotbridge.common.result.Result<T>`：统一响应（`success/code/message/data`，静态工厂）。
- `com.iotbridge.common.exception.BridgeException`：运行时业务异常 + `ErrorCodeEnum`。
- `com.iotbridge.common.util.JsonUtil`：Jackson 封装（字符串<->对象，`BigDecimal` 用 String）。

### iotbridge-spi （插件面向的稳定契约，尽量不引 Spring）
- `com.iotbridge.spi.codec.ProtocolCodec`：
  ```java
  public interface ProtocolCodec {
      String protocolType();                       // 插件唯一标识，如 "demo-text"
      UplinkMessage decode(byte[] frame);          // 设备原始帧 -> 上行消息
      byte[] encode(DownlinkCommand command);      // 下行指令 -> 设备原始帧
  }
  ```
- `com.iotbridge.spi.model.UplinkMessage`：`deviceId/protocolType/payload(Map<String,Object>)/timestamp`（包装类型）。
- `com.iotbridge.spi.model.DownlinkCommand`：`deviceId/protocolType/command/params(Map)/requestId`。
- `com.iotbridge.spi.frame.FrameRule`：默认分隔/聚合规则描述（供接入层选择 `DelimiterBasedFrameDecoder`/`LengthFieldBasedFrameDecoder`），demo 用 `\n` 行协议。

### iotbridge-plugin-demo
- `com.iotbridge.plugin.demo.DemoTextCodec implements ProtocolCodec`：行文本协议 `deviceId,key=value,key=value\n`。
  - `decode`：解析为 `UplinkMessage`；`encode`：`deviceId:command:param=v\n`。
- `src/main/resources/META-INF/services/com.iotbridge.spi.codec.ProtocolCodec` → 内容 `com.iotbridge.plugin.demo.DemoTextCodec`。

### iotbridge-mq
- `com.iotbridge.mq.api.UplinkProducer`：`void send(UplinkMessage)`。
- `com.iotbridge.mq.kafka.KafkaUplinkProducer implements UplinkProducer`：用 `KafkaTemplate` 发送，key=deviceId，topic=`iotbridge.uplink`；失败回调 `ERROR` 日志。
- `com.iotbridge.mq.rabbit.DownlinkCommandConsumer`：`@RabbitListener(queues="iotbridge.downlink")` 消费 `DownlinkCommand`，回调 `DownlinkDispatchHandler`（由 bridge-core 实现的接口，避免反向依赖）。
- `com.iotbridge.mq.api.DownlinkPublisher` + `com.iotbridge.mq.rabbit.RabbitDownlinkPublisher`：`RabbitTemplate.convertAndSend` 下发（供 admin 调用）。
- `com.iotbridge.mq.config.{KafkaProducerConfig, RabbitConfig}`：`@Configuration`，读取 `iotbridge.mq.*` 属性。
- `DownlinkDispatchHandler`（接口）置于 spi 或 mq 顶层，供 consumer 与 core 解耦。

### iotbridge-access-netty
- `com.iotbridge.access.netty.server.NettyTcpServer`：`ServerBootstrap`+`NioEventLoopGroup`(boss/worker，`ThreadPoolExecutor` 语义走 Netty)，pipeline：`StringAggregator/DelimiterBasedFrameDecoder(\n)` -> `DeviceInboundHandler`；端口来自 `iotbridge.netty.port`。
- `com.iotbridge.access.netty.handler.DeviceInboundHandler extends SimpleChannelInboundHandler<String>`：握手时注册 `deviceId<->Channel`；收到帧调用 `UplinkFrameListener`（接口，bridge-core 实现）。
- `com.iotbridge.access.netty.session.ChannelManager`：`ConcurrentHashMap<String,Channel>`，`register/getOffline/sendToDevice(deviceId, byte[])`，`channelInactive` 清理。
- `config.NettyProperties`：`@ConfigurationProperties("iotbridge.netty")`。

### iotbridge-bridge-core
- `com.iotbridge.bridge.core.registry.ProtocolCodecRegistry`：启动时 `ServiceLoader.load(ProtocolCodec.class)` 收集，按 `protocolType()` 建 `Map`，并解析 Spring 上下文中的 `@Component` 实现合并。
- `dispatch.UplinkDispatcher implements UplinkFrameListener`：选择 codec.decode -> 调 `UplinkProducer.send`。
- `dispatch.DownlinkDispatcher implements DownlinkDispatchHandler`：选择 codec.encode -> `ChannelManager.sendToDevice`；设备离线返回告警日志。
- `service.GatewayLifecycle`：`SmartLifecycle`/`ApplicationRunner` 启停 `NettyTcpServer`。

### iotbridge-admin
- `controller.CommandController`：`POST /api/v1/devices/{deviceId}/commands`，body=`DownlinkCommand`，`Result` 返回；调用 `DownlinkPublisher`。
- `dto.CommandRequest`（校验注解 `jakarta.validation`）。

### iotbridge-starter
- `com.iotbridge.IotbridgeApplication`：`@SpringBootApplication`+`@ComponentScan("com.iotbridge")`+`@EnableRabbit`。
- `resources/application.yml`：端口 8080；`spring.kafka.bootstrap-servers`、`spring.rabbitmq.*`；`iotbridge.netty.port=9000`；`iotbridge.protocol.default=demo-text`。
- `resources/application-local.yml`：本地 docker 默认地址。

## 5. 可运行端到端示例
- `iotbridge-starter/src/test/.../DemoDeviceClient`：Netty 客户端，连 `localhost:9000`，发送 `dev001,temperature=26.5,humidity=60\n`，并监听下行回写。
- 下行验证：`curl -XPOST localhost:8080/api/v1/devices/dev001/commands -d '{"command":"setInterval","params":{"value":30}}'` -> RabbitMQ -> DownlinkDispatcher -> 设备 TCP 收到 `dev001:setInterval:value=30`。
- 依赖中间件（README 内一行命令，非必需纳入工程）：`docker run ... confluentinc/cp-kafka` 与 `rabbitmq:3-management`；提供可选 `docker/docker-compose.yml` 便捷启动（不展开多环境/CI，保持范围）。

## 6. 文档与根文件
- 根 `README.md`：架构图（模块依赖+上下行时序简图）、快速开始（启动 MQ、`mvn -pl iotbridge-starter spring-boot:run`、跑 demo client、curl 下发）。
- `.gitignore`（target/.idea/*.iml）、`.mvn/wrapper`（可选，用 mvn 本地即可）。
- 各模块 `package-info.java` 简述职责。

## 7. 测试计划
- `iotbridge-plugin-demo`：`DemoTextCodecTest` 验证 decode/encode 往返一致。
- `iotbridge-bridge-core`：`ChannelManagerTest` 注册/下线；`ProtocolCodecRegistryTest` ServiceLoader 能加载 demo。
- 集成（可 `@Disabled` 或 local profile 手动跑）：起 Netty + 模拟 client，断言上行进入 mock `UplinkProducer`；下发经 mock consumer 到 `ChannelManager`。
- 编译验证：`mvn -q -DskipTests clean package` 全模块通过；`mvn -q test` 单测通过。

## 8. 假设
- MQ 地址/账号走配置，demo 默认 localhost；无真实平台时上行 Kafka 消费、下行来源由 admin REST 模拟。
- SPI 采用标准 `ServiceLoader`（非 Spring 全托管），保证插件可独立打成 jar 放入 classpath。
- 首版聚焦单协议 demo 与清晰扩展点；多协议并存仅需新增 plugin 模块 + services 文件。
- 不含鉴权/持久化设备影子/集群化会话（后续迭代），骨架预留接口位。