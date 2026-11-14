# 指标与监控

网关通过 Spring Boot Actuator + Micrometer 暴露 Prometheus 指标，默认端点：

```
GET /actuator/prometheus    # Prometheus 抓取
GET /actuator/health        # 总健康状态（聚合）
GET /actuator/health/liveness
GET /actuator/health/readiness
```

所有指标带公共标签 `application="iotbridge"`（由 `management.metrics.tags.application` 注入）。

## 1. 指标清单

| 指标 | 类型 | 标签 | 含义 | 建议关注 |
| --- | --- | --- | --- | --- |
| `iotbridge_uplink_frames_total` | counter | `protocol`, `result` | 上行帧处理结果。`result` = `ok` / `error` / `noDeviceId` | `error` 持续增长说明设备帧格式/CRC/协议绑定有问题；`noDeviceId` 说明协议解码或接入标识异常 |
| `iotbridge_uplink_dropped_total` | counter | — | 上行工作队列满被丢弃的帧数 | 任何非零增长都意味着**上行丢数据**，需扩容 `worker-threads` / `queue-capacity` 或排查下游 Kafka 阻塞 |
| `iotbridge_downlink_total` | counter | `result` | 下行投递结果 | 见下表「下行 result 语义」 |
| `iotbridge_sessions_active` | gauge | — | 当前在线会话数（`(protocolType, deviceId)` 计） | 骤降=设备大面积掉线或接入故障；持续为 0 但业务说有设备=监听端口/协议绑定问题 |
| `iotbridge_downlink_buffer_pending` | gauge | — | 离线暂存待补发指令总数 | 开启缓冲后应周期性回落；单调上升说明设备长期不在线或补发失败 |
| `iotbridge_downlink_buffer_devices` | gauge | — | 存在待补发指令的设备数 | 结合 `max-pending-devices` 上限评估容量 |
| `iotbridge_security_auth_total` | counter | `protocol`, `result` | 设备接入认证结果。`result` = `allowed` / `notConfigured` / `unknownDevice` / `disabled` / `badCredential` / `networkDenied` | `unknownDevice` 增长=有设备未登记（灰度期即是待补清单）；`badCredential`/`networkDenied` 增长=凭据泄露或 IP 漂移，需立即排查 |
| `iotbridge_reflow_total` | counter | `result` | 补发结果。`sent` / `failed` / `unknown` / `skipped` / `rejected` | `rejected` 增长=补发队列打满（设备集中重连）；`failed` 增长=通道反复失效；`unknown` 说明写等待超时 |

### 下行 `result` 语义（关键）

| result | 含义 | 是否重投 |
| --- | --- | --- |
| `delivered` | 写 future 确认成功 | 否 |
| `unknown` | 写等待超时/线程中断：**可能已送达** | **否**（重投会导致设备重复执行） |
| `buffered` | 确定未送达且开启暂存 | 是（设备上线后补发） |
| `dropped` | 确定未送达且未开启暂存 | 否（仅告警） |
| `duplicate` | 命中幂等去重（该 requestId 已送达过） | 否 |
| `error` | 协议解析/编码失败（确定性错误） | 否 |

> `unknown` 是本次新增语义的观测重点：它替代了旧版「超时=失败→暂存→重投」的重复下发风险。
> 出现 `unknown` 时应有设备侧应答或业务对账兜底（应答闭环尚未实现，见设计评审 P1-5）。

## 2. PromQL 示例

```promql
# 上行错误率（5 分钟）
sum(rate(iotbridge_uplink_frames_total{result="error"}[5m]))
  / sum(rate(iotbridge_uplink_frames_total[5m]))

# 上行丢帧（任意非零需告警）
increase(iotbridge_uplink_dropped_total[5m]) > 0

# 下行未确认占比
sum(rate(iotbridge_downlink_total{result="unknown"}[5m]))
  / sum(rate(iotbridge_downlink_total[5m]))

# 暂存积压
iotbridge_downlink_buffer_pending > 1000

# 补发队列拒绝
increase(iotbridge_reflow_total{result="rejected"}[5m]) > 0

# 未登记设备（灰度盘点 / 强制后即异常）
sum by (protocol) (rate(iotbridge_security_auth_total{result="unknownDevice"}[10m]))

# 凭据错误或来源受限（疑似冒名）
sum(rate(iotbridge_security_auth_total{result=~"badCredential|networkDenied"}[5m])) > 0

# 会话数骤降（与 1 小时前比较，跌幅 > 50%）
iotbridge_sessions_active < 0.5 * (iotbridge_sessions_active offset 1h)
```

## 3. 告警建议

| 条件 | 级别 | 处置提示 |
| --- | --- | --- |
| `increase(iotbridge_uplink_dropped_total[5m]) > 0` | P1 | 检查 Kafka 可用性、`max.block.ms`、上行线程/队列容量 |
| `unknown` 占比 > 5% 持续 10 分钟 | P1 | 检查设备弱网与出站水位；确认业务侧是否有对账 |
| `iotbridge_downlink_buffer_pending` 持续上升 30 分钟 | P2 | 检查设备在线率与补发失败日志 |
| `reflow{result="rejected"}` 出现 | P2 | 设备集中重连；评估 `reflow-queue-capacity` 与补发线程数 |
| `sessions_active` 跌幅 > 50%/1h | P1 | 检查接入端口、网络、设备批量掉电 |
| `security_auth{result="badCredential"}` 或 `networkDenied` 非零 | P1 | 疑似冒名接入或 IP 漂移；核对注册表与设备变更记录 |
| `security_auth{result="unknownDevice"}` 持续增长 | P2（灰度期）/ P1（强制后） | 补登记设备清单；强制后出现说明有未报备设备在尝试接入 |
| `/actuator/health/readiness` 非 UP | P1 | 依赖（Kafka/Rabbit）或接入服务异常 |

## 4. 日志与指标的对应

| 日志关键字 | 对应指标/含义 |
| --- | --- |
| `下行送达状态未知（写等待超时）` | `downlink{result="unknown"}` |
| `重复下行指令已跳过` | `downlink{result="duplicate"}` |
| `补发任务队列已满，本次补发已拒绝` | `reflow{result="rejected"}` |
| `补发写入状态未知` | `reflow{result="unknown"}` |
| `上行处理队列已满，丢弃该帧` | `uplink_dropped_total` |
| `设备接入被拒绝 ... result=` | `security_auth{result}`（强制模式拒绝） |
| `设备未通过接入认证但当前为观察模式，已放行` | `security_auth{result}`（观察模式，未拦截） |

日志文件位置与链路字段（`traceId` / `deviceId` / `requestId`）见部署文档《7. 日志》。

## 5. 已知边界

- `iotbridge_sessions_active` 是进程内视图：多实例部署时需按实例分别抓取后求和，不存在全局聚合。
- 缓冲相关 gauge 只统计本实例内存中的暂存数据，与「多实例缓冲不共享」的限制一致。
- 指标未做基数限制：`protocol` 标签来自配置（数量可控）；`result` 为固定枚举，不会出现高基数问题。
  `deviceId` **未**作为标签（避免高基数拖垮 Prometheus），按设备排障请用日志检索。
