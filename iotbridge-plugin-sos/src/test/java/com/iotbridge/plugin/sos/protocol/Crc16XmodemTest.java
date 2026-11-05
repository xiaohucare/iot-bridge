package com.iotbridge.plugin.sos.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Crc16Xmodem} 校验测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class Crc16XmodemTest {

    @Test
    void shouldMatchKnownXmodemCheckValue() {
        // CRC-16/XMODEM 对 "123456789" 的标准校验值为 0x31C3
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x31C3, Crc16Xmodem.compute(data));
    }

    @Test
    void shouldComputeOverSubRange() {
        byte[] data = {(byte) 0xAA, '1', '2', '3', (byte) 0xBB};
        // 仅对中间 "123" 计算，应等同于独立数组
        int subrange = Crc16Xmodem.compute(data, 1, 3);
        int standalone = Crc16Xmodem.compute("123".getBytes(StandardCharsets.US_ASCII));
        assertEquals(standalone, subrange);
    }
}
