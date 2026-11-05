package com.iotbridge.plugin.sos;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.plugin.sos.model.SosFrame;
import com.iotbridge.plugin.sos.protocol.HexUtil;
import com.iotbridge.plugin.sos.protocol.SosProtocolConstants;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * SOS-C02/C03B/W03-A/C03-AO 通信协议 V3.0 编解码插件。
 *
 * <p>上行：解析设备注册的 3 字节帧头 {@code 57 54 4B} 起、帧尾 {@code 45 4E 44} 止的二进制帧，
 * 校验 CRC16-XMODEM，并按命令（注册 0x01 / 事件 0x02 / 长连接 0x05 / 数据上报 0x06）拆解业务字段。
 * 下行：将平台指令编码为二进制帧（服务器回复 0xFF / 时间校准 0x0B / 指令下发 0x03）。</p>
 *
 * <p>本插件通过 {@code META-INF/services} 以 {@link java.util.ServiceLoader} 注册。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class SosProtocolCodec implements ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(SosProtocolCodec.class);

    /**
     * 设备类型字段在注册/事件/数据上报数据体内的下标。
     */
    private static final int OFF_DEVICE_TYPE = 0;
    /**
     * 设备编号(IMEI)在数据体内的下标。
     */
    private static final int OFF_IMEI = 1;

    @Override
    public String protocolType() {
        return SosProtocolConstants.PROTOCOL_TYPE;
    }

    @Override
    public UplinkMessage decode(byte[] frame) {
        SosFrame parsed = SosFrame.parse(frame);
        Map<String, Object> payload = parsePayload(parsed);
        String deviceId = resolveDeviceId(parsed);

        UplinkMessage message = new UplinkMessage(deviceId, SosProtocolConstants.PROTOCOL_TYPE,
                payload, System.currentTimeMillis());
        if (log.isDebugEnabled()) {
            log.debug("SOS 上行解析 command={} deviceId={} payload={}",
                    Integer.toHexString(parsed.getCommand() & 0xFF), deviceId, payload);
        }
        return message;
    }

    /**
     * 依据命令类型拆解数据体为业务字段。
     *
     * @param frame 已解析帧
     * @return 业务负载
     */
    private Map<String, Object> parsePayload(SosFrame frame) {
        Map<String, Object> payload = new HashMap<>();
        int cmd = frame.getCommand() & 0xFF;
        payload.put("command", cmd);
        payload.put("commandHex", String.format("%02X", cmd));
        payload.put("commandName", SosProtocolConstants.COMMAND_NAMES.getOrDefault(cmd, "未知命令"));
        payload.put("version", frame.getVersion() & 0xFF);
        payload.put("vendor", HexUtil.toHex(frame.getVendor()));
        payload.put("sequence", frame.getSequence());

        switch (cmd) {
            case 0x01:
                parseRegister(frame, payload);
                break;
            case 0x02:
                parseEvent(frame, payload);
                break;
            case 0x05:
                // 长连接包：仅 IMEI，无额外字段
                payload.put("imei", frame.readAscii(0, SosProtocolConstants.IMEI_LEN));
                break;
            case 0x06:
                parseDataReport(frame, payload);
                break;
            default:
                payload.put("rawHex", HexUtil.toHex(frame.getData()));
                break;
        }
        return payload;
    }

    /**
     * 注册包(0x01)：设备类型(1) + IMEI(15) + IMSI(15) + ICCID(20) + 电压(1) + CSQ(1) + 电量(1) + 程序版本(1)。
     */
    private void parseRegister(SosFrame frame, Map<String, Object> payload) {
        payload.put("deviceType", describeDeviceType(frame.readUnsignedByte(OFF_DEVICE_TYPE)));
        payload.put("imei", frame.readAscii(OFF_IMEI, SosProtocolConstants.IMEI_LEN));
        payload.put("imsi", frame.readAscii(16, 15));
        payload.put("iccid", frame.readAscii(31, 20));
        putBattery(frame, payload, 51);
    }

    /**
     * 事件包(0x02)：设备类型(1) + IMEI(15) + 事件类型(1) + 电压(1) + CSQ(1) + 电量(1) + 程序版本(1)。
     */
    private void parseEvent(SosFrame frame, Map<String, Object> payload) {
        payload.put("deviceType", describeDeviceType(frame.readUnsignedByte(OFF_DEVICE_TYPE)));
        payload.put("imei", frame.readAscii(OFF_IMEI, SosProtocolConstants.IMEI_LEN));
        int eventType = frame.readUnsignedByte(16);
        payload.put("eventType", eventType);
        payload.put("eventName", SosProtocolConstants.EVENT_TYPE_NAMES.get(eventType));
        putBattery(frame, payload, 17);
    }

    /**
     * 数据上报包(0x06)：设备类型(1) + IMEI(15) + 数据类型(1) + 内容（如位置 JSON，ASCII 字节）。
     */
    private void parseDataReport(SosFrame frame, Map<String, Object> payload) {
        payload.put("deviceType", describeDeviceType(frame.readUnsignedByte(OFF_DEVICE_TYPE)));
        payload.put("imei", frame.readAscii(OFF_IMEI, SosProtocolConstants.IMEI_LEN));
        int dataType = frame.readUnsignedByte(16);
        payload.put("dataType", dataType);
        byte[] rest = frame.readRemainBytes(17);
        if (rest.length > 0) {
            String content = new String(rest, StandardCharsets.UTF_8).trim();
            // 位置等类型以 ASCII JSON 承载，尽量解析为结构化字段
            if (content.startsWith("{")) {
                try {
                    payload.put("location", com.iotbridge.common.util.JsonUtil.parseMap(content));
                } catch (BridgeException e) {
                    payload.put("content", content);
                }
            } else {
                payload.put("content", content);
            }
        }
    }

    private void putBattery(SosFrame frame, Map<String, Object> payload, int base) {
        int voltage = frame.readUnsignedByte(base);
        int csq = frame.readUnsignedByte(base + 1);
        int battery = frame.readUnsignedByte(base + 2);
        int progVersion = frame.readUnsignedByte(base + 3);
        payload.put("batteryVoltage", String.format("%.1f", voltage / 10.0));
        payload.put("csq", csq);
        payload.put("batteryPercent", battery);
        payload.put("programVersion", formatProgramVersion(progVersion));
    }

    private String describeDeviceType(int type) {
        if (type < 0) {
            return "未知";
        }
        return SosProtocolConstants.DEVICE_TYPE_NAMES.getOrDefault(type, "类型0x" + String.format("%02X", type));
    }

    /**
     * 程序版本：0x10 -> 1.0，0x11 -> 1.1（高 nibble 主版本，低 nibble 次版本）。
     */
    private String formatProgramVersion(int raw) {
        if (raw < 0) {
            return "";
        }
        int major = (raw >> 4) & 0x0F;
        int minor = raw & 0x0F;
        return major + "." + minor;
    }

    /**
     * 解析设备唯一标识：优先取数据体内 IMEI；长连接包 IMEI 位于下标 0。
     */
    private String resolveDeviceId(SosFrame frame) {
        int cmd = frame.getCommand() & 0xFF;
        if (cmd == (SosProtocolConstants.CMD_KEEPALIVE & 0xFF)) {
            String imei = frame.readAscii(0, SosProtocolConstants.IMEI_LEN);
            if (!imei.isEmpty()) {
                return imei;
            }
        }
        if (cmd == (SosProtocolConstants.CMD_REGISTER & 0xFF)
                || cmd == (SosProtocolConstants.CMD_EVENT & 0xFF)
                || cmd == (SosProtocolConstants.CMD_DATA_REPORT & 0xFF)) {
            String imei = frame.readAscii(OFF_IMEI, SosProtocolConstants.IMEI_LEN);
            if (!imei.isEmpty()) {
                return imei;
            }
        }
        return HexUtil.toHex(frame.getData());
    }

    @Override
    public byte[] encode(DownlinkCommand command) {
        if (command == null) {
            throw new BridgeException(ErrorCodeEnum.ENCODE_ERROR, "下行指令为空");
        }
        Map<String, Object> params = command.getParams() == null ? Map.of() : command.getParams();
        byte[] vendor = parseVendor(params.get("vendor"));
        int seq = parseInt(params.get("seq"), 0);
        byte cmdByte = resolveCommandByte(command.getCommand());
        byte[] data = buildDownlinkData(cmdByte, params);

        SosFrame frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, vendor, seq, cmdByte, data);
        return frame.toBytes();
    }

    /**
     * 指令名 -> 命令字节。支持 {@code ack}/{@code serverReply}(0xFF)、{@code timeCalib}(0x0B)、
     * {@code cmd}/{@code down}(0x03)，或直接十六进制（如 {@code "03"}/{@code "0x03"}）。
     */
    private byte resolveCommandByte(String name) {
        if (name == null || name.isEmpty()) {
            return SosProtocolConstants.CMD_SERVER_REPLY;
        }
        switch (name.trim().toLowerCase()) {
            case "ack":
            case "serverreply":
            case "reply":
                return SosProtocolConstants.CMD_SERVER_REPLY;
            case "timecalib":
            case "time":
                return SosProtocolConstants.CMD_TIME_CALIB;
            case "cmd":
            case "down":
            case "command":
                return SosProtocolConstants.CMD_DOWN;
            default:
                byte[] hex;
                try {
                    hex = HexUtil.fromHex(name.trim().replace("0x", "").replace("0X", ""));
                } catch (IllegalArgumentException ex) {
                    // 非合法十六进制串（如把 demo-text 等其它协议的指令名误投到本协议）：
                    // 转成明确的业务异常，避免向消费者外泄底层“非法十六进制长度”这类晦涩报错
                    throw new BridgeException(ErrorCodeEnum.ENCODE_ERROR, "无法识别的下行命令: " + name, ex);
                }
                if (hex.length == 1) {
                    return hex[0];
                }
                throw new BridgeException(ErrorCodeEnum.ENCODE_ERROR, "无法识别的下行命令: " + name);
        }
    }

    /**
     * 依据命令构造下行数据体。
     *
     * @param cmdByte 命令字节
     * @param params  指令参数
     * @return 数据体字节
     */
    private byte[] buildDownlinkData(byte cmdByte, Map<String, Object> params) {
        if (cmdByte == SosProtocolConstants.CMD_TIME_CALIB) {
            long ts = parseLong(params.get("timestamp"), System.currentTimeMillis() / 1000);
            return new byte[]{
                    (byte) ((ts >> 24) & 0xFF),
                    (byte) ((ts >> 16) & 0xFF),
                    (byte) ((ts >> 8) & 0xFF),
                    (byte) (ts & 0xFF)};
        }
        if (cmdByte == SosProtocolConstants.CMD_SERVER_REPLY) {
            int flag = parseInt(params.get("replyFlag"), SosProtocolConstants.REPLY_OK);
            return new byte[]{(byte) flag};
        }
        // 指令下发(0x03)：优先使用调用方给定的 dataHex / data(ASCII)
        Object hexVal = params.get("dataHex");
        if (hexVal != null) {
            return HexUtil.fromHex(String.valueOf(hexVal));
        }
        Object dataVal = params.get("data");
        if (dataVal != null) {
            return String.valueOf(dataVal).getBytes(StandardCharsets.US_ASCII);
        }
        return new byte[0];
    }

    private byte[] parseVendor(Object vendorVal) {
        if (vendorVal == null) {
            return new byte[]{0x00, 0x00};
        }
        byte[] parsed = HexUtil.fromHex(String.valueOf(vendorVal));
        if (parsed.length == 2) {
            return parsed;
        }
        if (parsed.length == 1) {
            return new byte[]{0x00, parsed[0]};
        }
        throw new BridgeException(ErrorCodeEnum.ENCODE_ERROR, "厂商标识需为 2 字节十六进制: " + vendorVal);
    }

    private int parseInt(Object val, int def) {
        if (val == null) {
            return def;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(Object val, long def) {
        if (val == null) {
            return def;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(val).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
