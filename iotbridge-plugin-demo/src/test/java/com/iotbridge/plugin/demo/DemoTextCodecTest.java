package com.iotbridge.plugin.demo;

import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DemoTextCodec} 编解码往返测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class DemoTextCodecTest {

    private final DemoTextCodec codec = new DemoTextCodec();

    @Test
    void decodeShouldParseDeviceIdAndPayload() {
        byte[] frame = "dev001,temperature=26.5,humidity=60".getBytes(StandardCharsets.UTF_8);

        UplinkMessage message = codec.decode(frame);

        assertNotNull(message);
        assertEquals("dev001", message.getDeviceId());
        assertEquals(DemoTextCodec.PROTOCOL_TYPE, message.getProtocolType());
        assertEquals("26.5", message.getPayload().get("temperature"));
        assertEquals("60", message.getPayload().get("humidity"));
        assertNotNull(message.getTimestamp());
    }

    @Test
    void encodeShouldProduceColonDelimitedLine() {
        Map<String, Object> params = new HashMap<>();
        params.put("value", 30);
        DownlinkCommand command = new DownlinkCommand("dev001", DemoTextCodec.PROTOCOL_TYPE,
                "setInterval", params, "req-1");

        byte[] encoded = codec.encode(command);
        String text = new String(encoded, StandardCharsets.UTF_8);

        assertEquals("dev001:setInterval:value=30\n", text);
    }

    @Test
    void serviceLoaderShouldDiscoverCodec() {
        boolean found = java.util.ServiceLoader.load(com.iotbridge.spi.codec.ProtocolCodec.class)
                .stream()
                .anyMatch(p -> DemoTextCodec.PROTOCOL_TYPE.equals(p.get().protocolType()));
        assertTrue(found, "ServiceLoader 应能发现 DemoTextCodec");
    }
}
