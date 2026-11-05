package com.iotbridge.plugin.sos.protocol;

/**
 * CRC16-XMODEM 校验。
 *
 * <p>多项式 {@code x^16 + x^12 + x^5 + 1}（0x1021），初值 0x0000，无反射、无最终异或，
 * 与协议文档第十七节给出的 C 参考实现等价。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class Crc16Xmodem {

    private static final int POLY = 0x1021;

    private Crc16Xmodem() {
        // 工具类不允许实例化
    }

    /**
     * 计算字节数组整段的 CRC16-XMODEM 校验值。
     *
     * @param data 待校验数据，不可为 {@code null}
     * @return 16 位校验值（高位在前）
     */
    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    /**
     * 计算字节数组指定区间的 CRC16-XMODEM 校验值。
     *
     * @param data   待校验数据
     * @param offset 起始下标（含）
     * @param length 参与计算的字节数
     * @return 16 位校验值
     */
    public static int compute(byte[] data, int offset, int length) {
        int crc = 0x0000;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ POLY) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
