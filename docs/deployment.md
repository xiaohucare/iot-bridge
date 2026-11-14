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
| `IOTBRIDGE_API_KEY` | `iotbridge.admin.api-key` | 空（不鉴权） |
| `IOTBRIDGE_MQTT_USERNAME` / `IOTBRIDGE_MQTT_PASSWORD` | `iotbridge.mqtt.username` / `.password` | 空（允许匿名） |
| `HOSTNAME` | 多实例下行队列后缀 `iotbridge.mq.instance-id`（未显式配置时） | 容器主机名，非容器为 `local` |

```bash
KAFKA_SERVERS=kafka:9092 RABBIT_HOST=rabbit RABBIT_USER=prod RABBIT_PASS=secret \
IOTBRIDGE_API_KEY=<强随机串> \
  java -jar iotbridge-starter/target/iotbridge.jar --spring.profiles.active=sos
```

其余可调项见 README《配置参考》表（`iotbridge.netty.*`、`iotbridge.uplink.*`、`iotbridge.mqtt.*`、`iotbridge.mq.*`、`iotbridge.admin.*`、`iotbridge.protocol.default`）。

> 生产部署必须设置 `IOTBRIDGE_API_KEY`（未配置时启动仅告警，REST 接口对任何调用方开放）；MQTT 接入建议同时配置 `IOTBRIDGE_MQTT_USERNAME/PASSWORD`（未配置时允许匿名连接），并建议通过 `server.ssl.*` 为 REST 端口开启 TLS；设备接入端口 TCP（9000/9001）与 MQTT（1883）为明文，如需链路加密应在网关前置 TLS 终结或专用安全网关（MQTT TLS 常用 8883）。

### 4.4 生产安全基线（`prod` profile）

以 `--spring.profiles.active=prod` 启动时，`ProductionSecurityGuard` 会在容器启动阶段校验关键安全配置，
不满足时**直接拒绝启动**（而不是仅告警后以无鉴权状态对外服务）：

- `iotbridge.admin.api-key`（`IOTBRIDGE_API_KEY`）为空 → 启动失败；
- `iotbridge.mqtt.enabled=true` 但 `iotbridge.mqtt.username` 为空（允许匿名接入）→ 启动失败；
- 设备注册表未启用（`iotbridge.security.enabled=false`）或仍为观察模式（`enforce=false`）→ 启动失败。

非 prod profile 下同样场景只记录 WARN，便于本地开发。日志示例：

```
生产环境安全基线未满足，拒绝启动：iotbridge.admin.api-key 未配置（REST 下行指令将无鉴权）;
```

### 4.5 设备接入认证（P0-1）

网关按 `(协议, 设备号)` 校验设备注册表，堵住「知道设备号即可冒名接入、并收走该设备下行」的缺口。
设备号口径：TCP 为帧内 IMEI（`sos-c02`）或文本首字段（`demo-text`），MQTT 为 `clientId`。

```yaml
iotbridge:
  security:
    enabled: true            # 关闭 = 不校验（prod 拒绝启动）
    enforce: false           # true = 未通过即断链/CONNACK 5；false = 只告警放行
    devices:
      - protocol: sos-c02
        device-id: 861234567890123
        enabled: true
        allowed-ips: [10.20.0.15]        # 可选：来源 IP 绑定，支持 10.20.* 前缀
        note: 1 号楼消防主机
      - protocol: mqtt
        device-id: dev-mqtt
        enabled: true
        password: ${DEV_MQTT_PASSWORD:}  # 设备级密码（CONNECT password）
```

规则与行为：

- **凭据按「越配越严」**：配了 `password` 必须匹配（常量时间比较）；配了 `cert-fingerprint` 必须证书指纹匹配（忽略冒号/大小写）；配了 `allowed-ips` 必须来源命中。三者都不配时只校验设备是否在册。
- **未登记即拒绝**：注册表为空等于“谁都不信”，不会因为“没配置”而放行（配置项 `enabled=false` 才放行，仅限本地）。
- **吊销**：把 `enabled` 置 `false` 并保留记录，设备立即被拒，便于审计与恢复。
- **处置方式**：TCP 在会话登记前校验，未通过直接关闭连接且**不会**进入 `ChannelManager`（因此不会被下行寻址到）；MQTT 在 CONNACK 前校验，未通过回 `CONNACK 0x05（Not authorized）`。
- **灰度**：先 `enforce=false` 跑一段时间，用日志「设备未通过接入认证但当前为观察模式」与指标 `iotbridge_security_auth_total{result="unknownDevice"}` 盘点存量设备，补全清单后再切 `enforce=true`。

**已知边界**：SOS-C02 帧内没有认证字段，TCP 侧无法在不改固件的前提下证明“我是这台设备”，`allowed-ips` 与注册表只能提高冒名门槛（NAT/动态 IP 场景会失效）。强身份需要前置 mTLS：在网关前终结 TLS 并要求客户端证书，把证书指纹填入注册表 `cert-fingerprint`；`DeviceCredential` 已预留 `certFingerprint` 入参，但 Netty 侧尚未挂 `SslHandler`，属下一轮工作。

### 4.6 多实例部署

多实例必须让同一设备的指令恒定命中同一实例，否则会出现「指令投到 A、设备连接在 B」的静默失败（旧配置下固定 routing key 还会因多队列绑定造成广播重复下发）：

```yaml
iotbridge:
  mq:
    downlink-exchange-type: consistent-hash   # 需启用 rabbitmq_consistent_hash_exchange 插件
    instance-id: ""                           # 留空则取 HOSTNAME，容器内天然唯一
```

```bash
# 确认插件已启用（3.8+ 镜像自带）
rabbitmq-plugins enable rabbitmq_consistent_hash_exchange
```

启用后每实例队列为 `<downlink-queue>.<instance-id>`，以 `deviceId` 作 routing key 哈希，设备指令按哈希落到唯一队列。

### 4.7 MQTT 接入

内置 MQTT 3.1.1 服务端**默认关闭**（不绑定 1883），需要时显式启用；启用前请先配置鉴权并规划 TLS 终结：

```yaml
iotbridge:
  mqtt:
    enabled: true            # 默认 false；true 才监听 port
    port: 1883
    protocol-type: mqtt                       # 下行指令的 protocolType 需填该值
    uplink-topic-prefix: iotbridge/uplink     # 设备 PUBLISH 到此前缀下视为上行
    downlink-topic-prefix: iotbridge/downlink # 网关发布到 <前缀>/<clientId>
    qos: 0                                    # 下行 PUBLISH QoS：0/1
    username: ${IOTBRIDGE_MQTT_USERNAME:}
    password: ${IOTBRIDGE_MQTT_PASSWORD:}
```

- **设备身份**：CONNECT 的 `clientId` 即设备号，在线查询与下行寻址均用它，例如
  `GET /api/v1/devices/dev-mqtt/online?protocol=mqtt`。
- **设备必须订阅下行主题**（如 `iotbridge/downlink/dev-mqtt` 或 `iotbridge/downlink/#`），
  否则下行按未送达处理；开启 `iotbridge.downlink.buffer-enabled` 后可暂存至设备订阅后重投。
- **鉴权**：配置用户名后，CONNECT 用户名/密码不匹配将收到 CONNACK 拒绝并断开；
  密码通过请求体明文传输，公网部署请在前置 TLS 终结后接入。
- **容量边界**：支持 QoS0/1，不支持 MQTT 5.0、QoS2、retained 消息、Will 消息与跨连接消息路由。

```bash
# 验证：订阅下行 + 发布上行（clientId 即设备号 dev-mqtt）
mosquitto_sub -h <gateway> -p 1883 -i dev-mqtt -u "$IOTBRIDGE_MQTT_USERNAME" \
  -P "$IOTBRIDGE_MQTT_PASSWORD" -t iotbridge/downlink/dev-mqtt
mosquitto_pub -h <gateway> -p 1883 -i dev-mqtt -u "$IOTBRIDGE_MQTT_USERNAME" \
  -P "$IOTBRIDGE_MQTT_PASSWORD" -t iotbridge/uplink/dev-mqtt -m '{"temperature":26.5}'
```

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
     -H "X-Api-Key: ${IOTBRIDGE_API_KEY}" \
     -d '{"protocolType":"sos-c02","command":"timeCalib","params":{"vendor":"0001"}}'
```

成功响应（失败时 HTTP 状态随错误类别返回 400/401/409/503，`code` 保留具体业务错误码）：

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

- 门面：SLF4J + Logback；命名空间 `com.iotbridge` 默认 `INFO`，`root` 为 `INFO`；配置见 `iotbridge-starter/src/main/resources/logback-spring.xml`。
- 输出：控制台 + 滚动文件 `logs/iotbridge.log`（单文件 100MB、保留 30 天、总量上限 2GB），可用环境变量 `LOG_PATH` 改目录。
- 日志模板包含链路字段，便于串联一次请求：`[traceId][deviceId][requestId]`。`traceId` 由 REST 过滤器生成或透传 `X-Trace-Id`（同时回写响应头）；`deviceId`/`protocolType` 在上行处理与补发线程写入 MDC；`requestId` 在下行分发时写入。
- 定位一次下发：`grep '<requestId>' logs/iotbridge.log` 可看到「REST 接收 → 消费 → 写入结果」全链路。
- **会话登记日志已在每帧路径上消除**：同一连接重复上报不会再产生「设备上线」日志，只有首次绑定、重复上线踢旧连接、下线时才输出；排查某设备是否在持续上报请查看上行 Kafka 消费而非网关日志。
- 调整级别示例：

  ```bash
  java -jar iotbridge.jar --logging.level.com.iotbridge=DEBUG
  ```

- 上行 Kafka 投递失败、下行 CRC 失败、设备离线丢弃等均有 `ERROR`/`WARN` 记录，便于定位。

### 7.1 健康检查与指标（actuator）

```bash
curl -s localhost:8080/actuator/health            # {"status":"UP"}
curl -s localhost:8080/actuator/health/liveness   # 容器存活探针
curl -s localhost:8080/actuator/health/readiness  # 就绪探针（接入服务与依赖就绪）
curl -s localhost:8080/actuator/prometheus | grep iotbridge_
```

指标清单与告警建议见 [monitoring/metrics.md](./monitoring/metrics.md)。容器编排建议：
`livenessProbe` 指向 `/actuator/health/liveness`，`readinessProbe` 指向 `/actuator/health/readiness`。
生产环境如需隔离管理面，可设置独立的 `management.server.port` 并只在内网暴露。

## 8. 故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 设备连上但无上行 | 帧模式与协议不匹配（二进制用了 `DELIMITER`） | 二进制协议启用 `sos` profile 或设 `frame-mode=LENGTH_FIELD` |
| `CRC 校验失败` | 帧字节序/校验范围/数据被截断 | 核对 offset 9 处长度、`adjustment=5`、CRC 范围 `[0,11+N)` |
| `未找到协议插件` | `protocolType` 拼写错误 / 插件 jar 未在 classpath | 确认 `META-INF/services` 注册与依赖打包 |
| 下行无反应且日志 `设备离线` | 设备未在线或 `deviceId` 不一致 | 先 `GET /api/v1/devices/online` 确认在线及 deviceId；用上行返回的 IMEI 下发；确认已建立 TCP 会话并发过上行；如开启 `iotbridge.downlink.buffer-enabled`，离线指令会暂存并在设备上线后自动补发 |
| REST 返回 401 `A0301` | 未带或错带 API Key | 请求头加 `X-Api-Key: <key>` 或 `Authorization: Bearer <key>`；确认网关进程的 `IOTBRIDGE_API_KEY` 与调用方一致 |
| 启动失败 `默认协议 xxx 未注册` / `监听协议 xxx 未注册` | `iotbridge.protocol.default` 或 `listeners[].protocol-type` 拼写错误 / 插件未打包 | 按提示修正协议标识或补齐插件 jar；这是启动期 fail-fast 校验，避免带病运行 |
| 启动失败 `监听地址重复` / `端口非法` | 两个 listener 绑定同一 host:port，或端口超出 1–65535 | 修正 listeners 端口；同实例重复绑定必然失败，提前拦截 |
| 设备显示在线但下行长期失败 | 半开连接（设备断电/NAT 超时） | 检查是否误将 `reader-idle-seconds` 设为 0 关闭了空闲检测；默认 180s 会主动断开无上行连接 |
| 日志 `通道写缓冲已满，下行暂缓` | 设备侧接收过慢，出站积压超过高水位 | 按需调整 `iotbridge.netty.write-buffer-high/low-water-mark`；或排查设备网络/处理能力，指令会按离线策略暂存或丢弃 |
| 多实例下同一指令重复下发 / 下发到无连接的实例 | 仍使用 `direct` + 固定 routing key，多队列绑定被广播 | 设置 `iotbridge.mq.downlink-exchange-type=consistent-hash` 并启用 `rabbitmq_consistent_hash_exchange` 插件，见 4.4 |
| MQTT 设备 CONNACK 拒绝 | 协议版本非 3.1/3.1.1、clientId 为空/超 128、用户名密码不匹配 | 按 CONNACK 返回码分别修正；已配置 `iotbridge.mqtt.username` 时必须携带匹配凭据 |
| MQTT 在线但下行收不到 | 设备未 SUBSCRIBE 下行主题，或订阅过滤器不匹配 | 设备订阅 `iotbridge/downlink/<clientId>`（或 `iotbridge/downlink/#`）；必要时开启离线暂存 |
| MQTT 连接周期性断开 | 设备 keepalive 期间无任何报文，超过 1.5 倍 keepalive 被判空闲 | 让设备按 keepalive 周期发送 PINGREQ，或调大客户端 keepalive |
| 设备一直连不上、日志 `设备接入被拒绝 ... result=unknownDevice` | 设备号不在注册表（TCP 取帧内 IMEI/首字段，MQTT 取 clientId），或协议标识不匹配 | 按日志中的 `protocol/deviceId` 补登记；注意同一设备号在不同协议下是两条独立记录 |
| 日志 `result=networkDenied` | 设备公网出口 IP 变化，与 `allowed-ips` 不符 | 放宽/删除该设备的 IP 绑定，或改用 mTLS 证书指纹 |
| MQTT 返回 CONNACK 5 | 注册表校验未通过（未在册/停用/密码不符） | 核对 `iotbridge.security.devices` 中的 clientId 与密码；`CONNACK 4` 才是全局用户名密码错误 |
| 启动失败 `BridgeMetrics required a bean of type 'MeterRegistry'` | 运行时 classpath 缺 `spring-boot-starter-actuator`（常发生在 IDE 未刷新 Maven 依赖，或自行精简依赖） | 刷新/补齐 actuator 依赖；代码侧已做降级：无 `MeterRegistry` 时只告警并按空操作运行（此时 `/actuator/prometheus` 无网关指标） |
| 启动失败 `生产环境安全基线未满足` | `prod` 下缺少 `iotbridge.admin.api-key`，或 MQTT 启用但未配 `username` | 配置 `IOTBRIDGE_API_KEY` / `IOTBRIDGE_MQTT_USERNAME` 后重启；本地开发改用非 prod profile |
| 下行日志出现 `送达状态未知（写等待超时）` | 设备网络慢或出站积压，写等待 5s 未完成 | 该指令**不会**重投（可能已送达），需设备应答/业务对账确认；持续出现请评估水位与带宽 |
| 重复下发同一 requestId 被拒 | 命中投递幂等（该请求号已确认送达） | 属预期行为；如确需再次执行，请用新的 `requestId`/`Idempotency-Key` |
| 端口 1883 未被监听 | MQTT 默认关闭 | 需要 MQTT 时显式设置 `iotbridge.mqtt.enabled=true` |
| 端口 1883 启动失败 | 端口被占用或 `port` 非法 | 修改 `iotbridge.mqtt.port` / 释放端口；启动期已有明确校验与告警 |
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
- MQTT 接入为轻量设备侧 Broker（MQTT 3.1.1）：仅支持 CONNECT/PUBLISH/SUBSCRIBE/UNSUBSCRIBE/PING/DISCONNECT 与 QoS0/1；不支持 MQTT 5.0、QoS2、retained 消息、Will 消息与跨连接消息路由；设备须主动订阅下行主题（未订阅按未送达处理）。
- 已内置：下行三态投递语义（超时不重投）、投递级与 REST 级幂等、健康检查与 Prometheus 指标、日志链路字段、设备注册表准入（白名单/设备级密码/IP 绑定，见 4.5）。
- 仍未内置：TCP 侧的强身份（mTLS 客户端证书校验尚未接入 Netty，注册表白名单 + IP 绑定只是提高门槛）、指令**应答**闭环（设备 ACK 回传与 `requestId` 状态查询）、设备影子持久化、跨实例会话/离线缓冲共享、链路 TLS、TCP 侧连接数与速率限制——均预留扩展点，详见 `design-review-2026-11-14.md`。
