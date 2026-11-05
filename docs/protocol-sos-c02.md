# SOS-C02 V3.0 消防设备通信协议规范

> 适用设备：智能无线终端消防设备（燃气、烟感、温感、手报等）
> 传输方式：TCP 长连接；承载网络：GPRS / 4G / WIFI
> 对应实现：`iotbridge-plugin-sos`（协议标识 `sos-c02`）
> 参考实现类：`SosFrame`、`Crc16Xmodem`、`SosProtocolCodec`、`SosProtocolConstants`

## 1. 帧总体结构

所有多字节数值字段均为**大端（网络字节序）**。

```
+--------+--------+----------+------------+--------+------------+----------+--------+--------+
|  帧头  | 版本   | 厂商标识 | 数据序列号 |  命令  |  数据长度  | 数据体   | CRC16  |  帧尾  |
| 3 字节 | 1 字节 | 2 字节   | 2 字节     | 1 字节 | 2 字节(N)  | N 字节   | 2 字节 | 3 字节 |
+--------+--------+----------+------------+--------+------------+----------+--------+--------+
  57 54 4B  0x30     vendor       seq          cmd       N            data       crc     45 4E 44
```

| 字段 | 字节数 | 帧内偏移 | 说明 |
| --- | --- | --- | --- |
| 帧头 | 3 | 0–2 | 固定 `57 54 4B`（ASCII `WTK`） |
| 协议版本 | 1 | 3 | 当前 V3.0 = `0x30` |
| 厂商标识 | 2 | 4–5 | 厂商分配，用于区分设备厂商 |
| 数据序列号 | 2 | 6–7 | 帧流水号（0–65535 循环） |
| 命令 | 1 | 8 | 命令字，见第 4 节 |
| 数据长度 | 2 | 9–10 | 数据体字节数 `N` |
| 数据体 | N | 11 – (10+N) | 业务数据，布局随命令而变 |
| CRC16 | 2 | (11+N) – (12+N) | 校验码，见第 2 节 |
| 帧尾 | 3 | (13+N) – (15+N) | 固定 `45 4E 44`（ASCII `END`） |

- **固定开销** = `3 + 1 + 2 + 2 + 1 + 2 + 2 + 3 = 16` 字节。
- **整帧长度** = `16 + N`。

## 2. 校验（CRC16-XMODEM）

| 参数 | 值 |
| --- | --- |
| 算法 | CRC-16/XMODEM（CRC-CCITT） |
| 多项式 | `0x1021` |
| 初值 | `0x0000` |
| 输入/输出反转 | 无 |
| 校验范围 | 帧头 .. 数据体，即字节区间 `[0, 11+N)`，**不含** CRC 与帧尾 |
| 存放 | 大端，紧随数据体之后 |

标准测试向量：ASCII `"123456789"` → CRC = `0x31C3`。

校验失败时，服务器回复帧应以回复标识 `0x01`（CRC 失败）应答。

## 3. TCP 流切帧（长度字段）

二进制帧无分隔符，需按长度字段切帧。Netty 采用：

```java
new LengthFieldBasedFrameDecoder(maxFrameLength, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip)
//                              1024             9                  2                     5                    0
```

推导：`lengthFieldOffset=9` 处读取 2 字节得到 `N`；`lengthFieldEnd` 之后剩余字节 = `N(数据体) + 2(CRC) + 3(帧尾)`，而 `LengthFieldBasedFrameDecoder` 默认只把 `N` 计为“长度字段之后的字节数”，因此需 `lengthAdjustment = 2 + 3 = 5` 补上 CRC 与帧尾；`initialBytesToStrip=0` 向下游交付**完整帧**供 `SosFrame.parse` 校验头/尾/长度/CRC。

对应网关配置见 `application-sos.yml`：`frame-mode: LENGTH_FIELD`、`max-frame-length: 1024`。

## 4. 命令集

| 命令码 | 方向 | 名称 | 数据体 |
| --- | --- | --- | --- |
| `0x01` | 上行 | 设备注册 | 见 4.1 |
| `0x02` | 上行 | 设备事件上报 | 见 4.2 |
| `0x03` | 下行 | 指令下发 | 厂商自定义内容 |
| `0x04` | 上行 | 下发指令回复 | 设备对 `0x03` 的应答 |
| `0x05` | 上行 | 长连接（心跳）包 | IMEI(15) |
| `0x06` | 上行 | 数据上报 | 见 4.3 |
| `0x07` | 上行 | OTA 升级请求 | — |
| `0x08` | 下行 | OTA 服务器信息下发 | — |
| `0x09` | 上行 | OTA 升级状态上报 | — |
| `0x0A` | 下行 | OTA 升级取消指令 | — |
| `0x0B` | 下行 | 时间校准 | 4 字节 epoch 秒（大端） |
| `0xFF` | 下行 | 服务器回复 | 1 字节回复标识 |

### 服务器回复标识（`0xFF` 数据体）

| 值 | 含义 |
| --- | --- |
| `0x00` | 成功 |
| `0x01` | CRC 校验失败 |
| `0x02` | 解析失败 |
| `0x03` | 厂商标识非法 |

### 4.1 设备注册（`0x01`）数据体布局

| 偏移 | 字节 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | 设备类型 | 见第 5 节 |
| 1 | 15 | IMEI | ASCII，作为设备唯一标识 `deviceId` |
| 16 | 15 | IMSI | ASCII |
| 31 | 20 | ICCID | ASCII |
| 51 | 1 | 电池电压 | 单位 0.1V，如 `0x25`=37 → `3.7V` |
| 52 | 1 | CSQ | 信号强度 0–31 / 99 |
| 53 | 1 | 电量 | 百分比 0–100 |
| 54 | 1 | 程序版本 | 高 nibble.低 nibble，如 `0x10`→`1.0` |

### 4.2 事件上报（`0x02`）数据体布局

| 偏移 | 字节 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | 设备类型 | 见第 5 节 |
| 1 | 15 | IMEI | ASCII |
| 16 | 1 | 事件类型 | 见第 6 节 |
| 17 | 1 | 电池电压 | 单位 0.1V |
| 18 | 1 | CSQ | 信号强度 |
| 19 | 1 | 电量 | 百分比 |
| 20 | 1 | 程序版本 | 高.低 nibble |

### 4.3 数据上报（`0x06`）数据体布局

| 偏移 | 字节 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | 设备类型 | 见第 5 节 |
| 1 | 15 | IMEI | ASCII |
| 16 | 1 | 数据类型 | 如位置上报 |
| 17 | N-17 | 内容 | ASCII；若为 JSON 则解析进 `payload.location`，否则原样放 `payload.content` |

## 5. 设备类型

| 码 | 名称 | 码 | 名称 |
| --- | --- | --- | --- |
| `0x01` | 门磁 | `0x0B` | 红外报警器 |
| `0x02` | 燃气 | `0x0C` | 定位器 |
| `0x03` | 烟感 | `0x0D` | 人体存在 |
| `0x04` | 手报(紧急按钮) | `0x0E` | 井盖 |
| `0x05` | 一氧化碳 | `0x0F` | 水压 |
| `0x06` | 温感 | `0x10` | 液位 |
| `0x07` | 声光 | `0x11` | 消火栓 |
| `0x08` | 水浸 | `0x12` | 多按键紧急按钮 |
| `0x09` | 报警器 | `0x13` | 智能胸牌 |
| `0x0A` | 断电报警器 | | |

## 6. 事件类型

| 码 | 名称 | 码 | 名称 |
| --- | --- | --- | --- |
| `0x01` | 心跳 | `0x11` | 无人报警恢复 |
| `0x02` | 报警 | `0x12` | 有人报警 |
| `0x03` | 报警恢复 | `0x13` | 有人报警恢复 |
| `0x04` | 防拆报警 | `0x14` | 驻留报警 |
| `0x05` | 防拆报警恢复 | `0x15` | 驻留报警恢复 |
| `0x06` | 低电压 | `0x16` | 跌倒报警 |
| `0x07` | 低电压恢复 | `0x17` | 跌倒报警恢复 |
| `0x08` | 传感器故障 | `0x18` | 静止驻留报警 |
| `0x09` | 传感器故障恢复 | `0x19` | 静止驻留报警恢复 |
| `0x0A` | 测试报警 | `0x20` | 按键触发 |
| `0x0B` | 测试报警恢复 | | |
| `0x0C` | 紧急报警 | | |
| `0x0D` | 紧急报警恢复 | | |
| `0x0E` | 温度报警 | | |
| `0x0F` | 温度报警恢复 | | |
| `0x10` | 无人报警 | | |

## 7. 上行解码后的统一消息（`UplinkMessage.payload`）

所有上行 `payload` 均含公共字段：`command`(int)、`commandHex`、`commandName`、`version`、`vendor`(hex)、`sequence`；并按命令追加如下：

| 命令 | 追加字段 |
| --- | --- |
| `0x01` | `deviceType`,`imei`,`imsi`,`iccid`,`batteryVoltage`,`csq`,`batteryPercent`,`programVersion` |
| `0x02` | `deviceType`,`imei`,`eventType`,`eventName`,`batteryVoltage`,`csq`,`batteryPercent`,`programVersion` |
| `0x05` | `imei` |
| `0x06` | `deviceType`,`imei`,`dataType`,（JSON→`location`，否则→`content`） |
| 其他 | `rawHex`（数据体十六进制） |

`deviceId` 解析规则：注册/事件/数据上报取数据体偏移 1 的 IMEI；心跳取偏移 0 的 IMEI；均回退为数据体十六进制。

## 8. 下行编码规则（`SosProtocolCodec.encode`）

由平台 `DownlinkCommand` 生成二进制帧。公共参数：`params.vendor`（2 字节十六进制，默认 `0000`）、`params.seq`（默认 0）。

| `command`（不区分大小写） | 命令字节 | 数据体 | 相关 `params` |
| --- | --- | --- | --- |
| `ack` / `serverReply` / `reply` | `0xFF` | 1 字节回复标识 | `replyFlag`（默认 `0`） |
| `timeCalib` / `time` | `0x0B` | 4 字节大端 epoch 秒 | `timestamp`（缺省取当前秒） |
| `cmd` / `down` / `command` | `0x03` | 自定义 | `dataHex`（十六进制）**或** `data`（ASCII） |
| 直接十六进制（如 `03` / `0x03`） | 1 字节 | 空 | — |

## 9. 帧构造与校验示例（Java）

```java
// 组注册帧
ByteArrayOutputStream data = new ByteArrayOutputStream();
data.write(0x04);                       // 设备类型：手报
// IMEI / IMSI / ICCID 按定长 ASCII 写入 ...
data.write(0x25); data.write(0x15); data.write(0x64); data.write(0x10); // 电压/CSQ/电量/版本
SosFrame frame = new SosFrame((byte) 0x30, new byte[]{0x00, 0x01}, 0x0001,
        (byte) 0x01, data.toByteArray());
byte[] wire = frame.toBytes();          // 自动填充数据长度、CRC16 与帧尾

// 解析校验（头/尾/长度/CRC 任一不符抛 BridgeException）
SosFrame parsed = SosFrame.parse(wire);
```

端到端演示见 `iotbridge-starter` 测试目录下的 `SosDemoClient.main`（默认 IMEI `869975034441082`、vendor `0001`，依次发送注册帧与报警事件帧）。

## 10. 落地建议

- 建议在设备首次注册后立即回 `0xFF`（`replyFlag=0`）建立会话，并以 IMEI 作为设备主键。
- 心跳（`0x05`）用于在线保活，超时未收到应触发离线判定（当前骨架依赖 TCP `channelInactive`）。
- 协议文档样例帧中厂商码/CRC 为占位，本项目测试采用“合法自造帧 + 回解析自洽 + 篡改 CRC 必失败”策略；若取得含真实值的完整样例帧，可补充逐字节比对用例。
