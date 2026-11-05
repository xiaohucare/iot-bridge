package com.iotbridge.plugin.sos;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.plugin.sos.model.SosFrame;
import com.iotbridge.plugin.sos.protocol.SosProtocolConstants;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SosProtocolCodec} 编解码测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class SosProtocolCodecTest {

    private static final String IMEI = "869975034441082";
    private static final byte[] VENDOR = {0x00, 0x01};

    private final SosProtocolCodec codec = new SosProtocolCodec();

    @Test
    void protocolTypeShouldMatch() {
        assertEquals(SosProtocolConstants.PROTOCOL_TYPE, codec.protocolType());
    }

    @Test
    void decodeRegisterFrame() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x04); // 手报
        ascii(data, IMEI, 15);
        ascii(data, "460113118743732", 15);
        ascii(data, "89861120224014398762", 20);
        data.write(0x25); // 3.7V
        data.write(0x15); // CSQ
        data.write(0x64); // 100%
        data.write(0x10); // v1.0
        byte[] frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0001,
                SosProtocolConstants.CMD_REGISTER, data.toByteArray()).toBytes();

        UplinkMessage message = codec.decode(frame);

        assertEquals(IMEI, message.getDeviceId());
        assertEquals(SosProtocolConstants.PROTOCOL_TYPE, message.getProtocolType());
        Map<String, Object> p = message.getPayload();
        assertEquals(1, p.get("command"));
        assertEquals("设备注册", p.get("commandName"));
        assertEquals("手报(紧急按钮)", p.get("deviceType"));
        assertEquals("3.7", p.get("batteryVoltage"));
        assertEquals(100, p.get("batteryPercent"));
        assertEquals("1.0", p.get("programVersion"));
        assertEquals("460113118743732", p.get("imsi"));
    }

    @Test
    void decodeEventFrameShouldMapAlarm() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x04);
        ascii(data, IMEI, 15);
        data.write(0x02); // 报警
        data.write(0x25);
        data.write(0x15);
        data.write(0x64);
        data.write(0x10);
        byte[] frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0002,
                SosProtocolConstants.CMD_EVENT, data.toByteArray()).toBytes();

        UplinkMessage message = codec.decode(frame);

        assertEquals(IMEI, message.getDeviceId());
        assertEquals(2, message.getPayload().get("eventType"));
        assertEquals("报警", message.getPayload().get("eventName"));
    }

    @Test
    void decodeKeepaliveShouldResolveImei() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ascii(data, IMEI, 15);
        byte[] frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0003,
                SosProtocolConstants.CMD_KEEPALIVE, data.toByteArray()).toBytes();

        UplinkMessage message = codec.decode(frame);

        assertEquals(IMEI, message.getDeviceId());
        assertEquals("长连接数据包", message.getPayload().get("commandName"));
    }

    @Test
    void decodeDataReportLocationShouldParseJson() {
        String json = "{\"gps\":\"22.573923,113.873557\"}";
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x04);
        ascii(data, IMEI, 15);
        data.write(0x04); // 数据类型：位置
        data.write(json.getBytes(StandardCharsets.UTF_8), 0, json.length());
        byte[] frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0004,
                SosProtocolConstants.CMD_DATA_REPORT, data.toByteArray()).toBytes();

        UplinkMessage message = codec.decode(frame);

        assertEquals(IMEI, message.getDeviceId());
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) message.getPayload().get("location");
        assertEquals("22.573923,113.873557", location.get("gps"));
    }

    @Test
    void encodeTimeCalibShouldProduceValidFrame() {
        Map<String, Object> params = new HashMap<>();
        params.put("vendor", "0001");
        params.put("seq", 5);
        params.put("timestamp", 1679969509L);
        DownlinkCommand command = new DownlinkCommand(IMEI, SosProtocolConstants.PROTOCOL_TYPE,
                "timeCalib", params, "req-1");

        byte[] encoded = codec.encode(command);

        // 回解析验证结构完整性（头部/尾部/CRC 自洽）
        SosFrame parsed = SosFrame.parse(encoded);
        assertEquals(SosProtocolConstants.CMD_TIME_CALIB, parsed.getCommand());
        assertEquals(5, parsed.getSequence());
        assertEquals(4, parsed.getData().length);
        assertEquals("0001", com.iotbridge.plugin.sos.protocol.HexUtil.toHex(parsed.getVendor()));
    }

    @Test
    void encodeUnknownCommandNameShouldThrowBridgeExceptionNotHexError() {
        // 把 demo-text 风格指令名误投到本协议：应给出可读的业务异常，而非“非法十六进制长度”这类晦涩报错
        Map<String, Object> params = new HashMap<>();
        params.put("vendor", "0001");
        DownlinkCommand command = new DownlinkCommand(IMEI, SosProtocolConstants.PROTOCOL_TYPE,
                "setInterval", params, "req-bad");

        BridgeException ex = assertThrows(BridgeException.class, () -> codec.encode(command));
        assertTrue(ex.getMessage().contains("无法识别的下行命令"), ex.getMessage());
    }

    @Test
    void decodeShouldFailOnCorruptedCrc() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ascii(data, IMEI, 15);
        byte[] frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0009,
                SosProtocolConstants.CMD_KEEPALIVE, data.toByteArray()).toBytes();
        // 篡改数据体一个字节，破坏 CRC
        frame[12] ^= 0xFF;

        assertThrows(BridgeException.class, () -> codec.decode(frame));
    }

    @Test
    void serviceLoaderShouldDiscoverSosCodec() {
        boolean found = java.util.ServiceLoader.load(com.iotbridge.spi.codec.ProtocolCodec.class)
                .stream()
                .anyMatch(p -> SosProtocolConstants.PROTOCOL_TYPE.equals(p.get().protocolType()));
        assertTrue(found, "ServiceLoader 应能发现 sos-c02 插件");
    }

    private static void ascii(ByteArrayOutputStream out, String text, int fixedLen) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < fixedLen; i++) {
            out.write(i < bytes.length ? bytes[i] : 0x30);
        }
    }
}
