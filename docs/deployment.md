# iotbridge 部署与运维手册

面向生产/联调的构建、配置、启动、接口调用与故障排查指南。协议细节见 [SOS-C02 V3.0 协议规范](./protocol-sos-c02.md)，工程总览见 [README](../README.md)。

## 1. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | 编译基线 `maven.compiler.release=17` |
| Maven | 3.9+ | 多模块构建 |
| Kafka | 3.7（示例用 `apache/kafka:3.7.0`，KRaft 单节点） | 上行消息 |
| RabbitMQ | 3.12（示例用 `rabbitmq:3.12-management`） | 下行指令 |

默认端口：HTTP `8080`、设备 TCP `9000`（SOS）/ `9001`（demo-text）、Kafka `9092`、RabbitMQ `5672`（管理台 `15672`）。

## 2. 构建

```bash
# 全量构建并打包（跳过测试）
mvn -q -DskipTests clean package

# 可执行 jar（由 spring-boot 插件 repackage）
ls iotbridge-starter/target/iotbridge.jar
```

## 3. 启动中间件（本地联调）

```bash
docker compose -f docker/docker-compose.yml up -d
```

- Kafka：`PLAINTEXT://localhost:9092`，已开启 topic 自动创建，上行主题 `iotbridge.uplink` 首次投递即生成。
- RabbitMQ：`localhost:5672`，用户/密码 `guest/guest`，管理台 `http://localhost:15672`。

生产环境请替换为具备鉴权、持久化与多副本的集群，并关闭匿名/默认账号。

## 4. 运行网关

### 4.1 默认：多协议监听

基线 `application.yml` 已默认启用 `iotbridge.netty.listeners`，直接启动即在同一实例并行监听：`9000` 走 SOS-C02（`LENGTH_FIELD`）、`9001` 走 demo-text（`DELIMITER`）；未绑定协议的端口回退 `iotbridge.protocol.default`（基线为中性示例协议 `demo-text`，多协议部署请在 listeners 中显式绑定、下行指令显式携带 `protocolType`）。

```bash
mvn -pl iotbridge-starter spring-boot:run
# 或： java -jar iotbridge-starter/target/iotbridge.jar
```

### 4.2 SOS 二进制协议（sos profile）

```bash
mvn -pl iotbridge-starter spring-boot:run -Dspring-boot.run.profiles=sos
# 或： java -jar iotbridge-starter/target/iotbridge.jar --spring.profiles.active=sos
```

`sos` profile 会：设默认协议为 `sos-c02`、将帧切分切换为 `LENGTH_FIELD`（offset 9 / len 2 / adjustment 5 / strip 0）、`max-frame-length=1024`。

### 4.3 外部化配置

关键连接项支持环境变量覆盖（见 `application.yml`）：

| 变量 | 覆盖配置 | 默认 |
| --- | --- | --- |
| `KAFKA_SERVERS` | `spring.kafka.bootstrap-servers` | localhost:9092 |
| `RABBIT_HOST` / `RABBIT_PORT` | `spring.rabbitmq.host` / `.port` | localhost / 5672 |
| `RABBIT_USER` / `RABBIT_PASS` | `spring.rabbitmq.username` / `.password` | guest / guest |

```bash
KAFKA_SERVERS=kafka:9092 RABBIT_HOST=rabbit RABBIT_USER=prod RABBIT_PASS=secret \
  java -jar iotbridge-starter/target/iotbridge.jar --spring.profiles.active=sos
```

其余可调项见 README《配置参考》表（`iotbridge.netty.*`、`iotbridge.mq.*`、`iotbridge.protocol.default`）。

## 5. 端到端验证

### 5.1 上行（设备 → 网关 → Kafka）

以 `sos` profile 启动后，运行 `SosDemoClient.main`（IDE 运行，需 `iotbridge-starter` 测试类路径），发送注册帧与事件帧后，观察 Kafka 主题：

```bash
docker exec -it iotbridge-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic iotbridge.uplink --from-beginning
```

应看到形如：

```json
{"deviceId":"869975034441082","protocolType":"sos-c02","payload":{"command":1,"commandName":"设备注册","imei":"869975034441082","deviceType":"手报(紧急按钮)","batteryVoltage":"3.7","csq":21,"batteryPercent":100,"programVersion":"1.0"},"timestamp":1762310400000}
```

### 5.2 下行（平台 → RabbitMQ → 设备）

向已连接的设备 IMEI 下发时间校准：

```bash
curl -XPOST http://localhost:8080/api/v1/devices/869975034441082/commands \
     -H 'Content-Type: application/json' \
     -d '{"protocolType":"sos-c02","command":"timeCalib","params":{"vendor":"0001"}}'
```

成功响应：

```json
{"success":true,"code":"0","message":"成功","data":"<requestId-uuid>"}
```

设备端（`SosDemoClient`）将打印收到的二进制下行帧。

### 5.3 跨协议会话隔离自动化脚本

`scripts/e2e-session-isolation.sh` 会拉起一个自带两个协议监听（SOS + demo-text）的网关，并以**相同 deviceId** 分别接入两台设备，自动断言：在线列表含两条同号会话、`?protocol=` 可分别聚焦、聚合/精确在线均为真，且向两个协议会话分别下行的帧均能送达对应设备（脚本会为自身实例指定独立下行队列，避开与常驻实例争抢）。结束后自动清理进程与临时文件。

```bash
# 可覆盖环境变量：ADMIN_PORT / SOS_PORT / TEXT_PORT / DEVICE_ID（默认已避开 8080/9000/9001）
./scripts/e2e-session-isolation.sh
```

> 多实例提示：若本机同时跑多个网关（如 IDE 启动的实例与脚本启动的实例），它们默认监听**同一个下行队列** `iotbridge.downlink`，RabbitMQ 会在多个消费者间轮转分发，下行指令可能被“没有该设备会话”的实例消费而下发失败。联调时可为某个实例指定独立队列（`--iotbridge.mq.downlink-queue=xxx` 及其配套的 `downlink-exchange`/`downlink-routing-key`），队列声明、消费监听与发布路由均取同一配置。

REST 接口定义：

- `POST /api/v1/devices/{deviceId}/commands`：下发指令，请求体字段 `command`(必填)、`params`、`protocolType`（为空回退默认协议）。多协议并行时 `protocolType` 需与设备上线时绑定的协议一致，否则会寻址到另一个 `(协议,设备号)` 会话（或找不到而判离线）。
- `GET /api/v1/devices/online`：列出当前在线设备（`protocolType` + `deviceId` + `remoteAddress`）。
- `GET /api/v1/devices/{deviceId}/online`：查询单设备是否在线（返回 `true`/`false`）；可选查询参数 `protocol`，传入则按协议精确判定，省略则跨协议任一在线即算在线。

> 下发前若不确定设备状态，先调 `GET /api/v1/devices/online` 核对，避免“下行未送达，设备离线”。

## 6. 生命周期与优雅停机

- Netty 服务端由 `GatewayLifecycle`（`SmartLifecycle`，`phase=MAX`）随容器刷新自动启动、随关闭自动停止（优雅释放 boss/worker）。
- 收到 `SIGTERM` 时 Spring 上下文关闭，触发 `stop()`；确保前置负载均衡先摘除 TCP 流量。

## 7. 日志

- 门面：SLF4J + Logback；命名空间 `com.iotbridge` 默认 `DEBUG`，`root` 为 `INFO`。
- 调整级别示例：

  ```bash
  java -jar iotbridge.jar --logging.level.com.iotbridge=INFO
  ```

- 上行 Kafka 投递失败、下行 CRC 失败、设备离线丢弃等均有 `ERROR`/`WARN` 记录，便于定位。

## 8. 故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 设备连上但无上行 | 帧模式与协议不匹配（二进制用了 `DELIMITER`） | 二进制协议启用 `sos` profile 或设 `frame-mode=LENGTH_FIELD` |
| `CRC 校验失败` | 帧字节序/校验范围/数据被截断 | 核对 offset 9 处长度、`adjustment=5`、CRC 范围 `[0,11+N)` |
| `未找到协议插件` | `protocolType` 拼写错误 / 插件 jar 未在 classpath | 确认 `META-INF/services` 注册与依赖打包 |
| 下行无反应且日志 `设备离线` | 设备未在线或 `deviceId` 不一致 | 先 `GET /api/v1/devices/online` 确认在线及 deviceId；用上行返回的 IMEI 下发；确认已建立 TCP 会话并发过上行；如开启 `iotbridge.downlink.buffer-enabled`，离线指令会暂存并在设备上线后自动补发 |
| Kafka/Rabbit 连接失败 | 地址/凭据错误或中间件未就绪 | 用环境变量覆盖 `KAFKA_SERVERS`、`RABBIT_*`；先起中间件再起网关 |
| RabbitMQ 反复报 `channel error ... 406 PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' for queue 'iotbridge.downlink'` | RabbitMQ 中已存在旧版本网关声明的同名队列（无死信参数）；RabbitMQ 不允许对已存在队列变更参数，新声明被拒绝 | 删除旧队列后重启网关，应用会按新声明（含死信参数）自动重建：管理台 http://localhost:15672 → Queues → Delete，或 `curl -u guest:guest -X DELETE http://localhost:15672/api/queues/%2F/iotbridge.downlink`。注意：删除会清空该队列未消费的下行指令 |

## 9. 扩展协议插件的部署

1. 按 README《扩展新协议》实现 `ProtocolCodec` 并 `ServiceLoader` 注册。
2. 打包插件 jar，作为 `iotbridge-starter` 的 `compile`/`runtime` 依赖，或放入启动目录 classpath：

   ```bash
   java -cp "plugins/*:iotbridge-starter/target/iotbridge.jar" com.iotbridge.IotbridgeApplication
   ```

3. 按需设置 `iotbridge.protocol.default`（决定上行解码协议）；下行按各指令 `protocolType` 精确路由。
4. 非行分隔协议需同步配置帧切分参数。

## 10. 已知限制（规划增强项）

- 上行支持按端口的多协议监听（`iotbridge.netty.listeners[].protocol-type`）：同一实例可在不同端口并行跑不同切帧方式与解码协议的多种协议；未配置 listeners 则用扁平字段合成单监听（向后兼容）。单端口内的自动协议识别（根据首帧内容嗅探）尚未实现，不同帧长的二进制协议仍需分端口接入。
- 下行离线暂存重投为可选（`iotbridge.downlink.buffer-enabled`，默认关闭）：开启后按 `(protocolType, deviceId)` 在有界队列暂存**原始指令**，设备重新上线（收到上行）时由 `DownlinkReflowService` 按会话串行、**重新编码**后按序补发（规避 `timeCalib` 等时间戳指令失效）；补发中发送失败会把剩余帧按原序退回缓冲等待下次上线，超 TTL 或超容量丢弃；内存受双重上限约束（单设备 `max-pending-per-device` + 设备数 `max-pending-devices`，后者超限时淘汰最陈旧设备队列）；仅当前实例内存，多实例不共享、进程重启即清空。
- 会话以 `(protocolType, deviceId)` 复合键隔离（`SessionKey`）：在线注册、下行投递、离线暂存与补发均按此键寻址，不同协议下的相同 `deviceId` 各自独立、互不覆盖；`GET /api/v1/devices/{deviceId}/online` 不传 `protocol` 时为跨协议聚合判定。
- 未内置设备鉴权、指令幂等与应答闭环、设备影子持久化、集群会话共享与指标监控——均预留扩展点。
