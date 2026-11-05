package com.iotbridge.plugin.sos.protocol;

/**
 * 十六进制字符串与字节数组转换工具。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class HexUtil {

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    private HexUtil() {
        // 工具类不允许实例化
    }

    /**
     * 字节数组转大写十六进制字符串（无分隔）。
     *
     * @param bytes 字节数组，可为 {@code null}
     * @return 十六进制字符串；入参为空返回空串
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX_CHARS[(b >> 4) & 0x0F]).append(HEX_CHARS[b & 0x0F]);
        }
        return sb.toString();
    }

    /**
     * 十六进制字符串转字节数组，允许包含空格且长度必须为偶数。
     *
     * @param hex 十六进制字符串
     * @return 字节数组；入参为 {@code null}/空返回空数组
     */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String clean = hex.replaceAll("\\s", "");
        int len = clean.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("非法的十六进制字符串长度: " + len);
        }
        byte[] result = new byte[len / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("非法的十六进制字符: " + clean);
            }
            result[i] = (byte) ((hi << 4) + lo);
        }
        return result;
    }
}
