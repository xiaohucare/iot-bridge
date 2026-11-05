package com.iotbridge.spi.frame;

/**
 * 帧聚合规则描述，供接入层选择合适的 Netty 解码器。
 *
 * <p>骨架示例使用 {@link Type#DELIMITER} 行协议；二进制长度前缀协议可扩展为
 * {@link Type#LENGTH_FIELD}。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class FrameRule {

    /**
     * 帧聚合规则类型。
     */
    public enum Type {
        /**
         * 基于分隔符（如 {@code \n}）。
         */
        DELIMITER,
        /**
         * 基于长度字段前缀。
         */
        LENGTH_FIELD
    }

    /**
     * 规则类型。
     */
    private Type type = Type.DELIMITER;

    /**
     * 分隔符（DELIMITER 类型有效）。
     */
    private String delimiter = "\n";

    /**
     * 单帧最大长度，防止内存溢出。
     */
    private Integer maxFrameLength = 1024 * 64;

    /**
     * 构造默认行分隔协议规则。
     *
     * @return 使用 {@code \n} 分隔的 {@link FrameRule}
     */
    public static FrameRule delimiterLine() {
        return new FrameRule();
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public Integer getMaxFrameLength() {
        return maxFrameLength;
    }

    public void setMaxFrameLength(Integer maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    public String toString() {
        return "FrameRule{type=" + type + ", delimiter='" + delimiter
                + "', maxFrameLength=" + maxFrameLength + '}';
    }
}
