package com.iotbridge.access.netty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Netty 接入层可配置属性，前缀 {@code iotbridge.netty}。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@ConfigurationProperties(prefix = "iotbridge.netty")
public class NettyProperties {

    /**
     * 监听地址。
     */
    private String host = "0.0.0.0";

    /**
     * 监听端口。
     */
    private Integer port = 9000;

    /**
     * boss 线程数。
     */
    private Integer bossThreads = 1;

    /**
     * worker 线程数，0 表示由 Netty 依 CPU 核数默认。
     */
    private Integer workerThreads = 0;

    /**
     * 单帧最大长度，防止异常大包耗尽内存。
     */
    private Integer maxFrameLength = 1024 * 64;

    /**
     * 帧切分模式：DELIMITER 行分隔（文本协议），LENGTH_FIELD 长度字段（二进制协议）。
     */
    private FrameMode frameMode = FrameMode.DELIMITER;

    /**
     * 长度字段偏移（LENGTH_FIELD 生效）。SOS V3.0 为 9。
     */
    private Integer lengthFieldOffset = 9;

    /**
     * 长度字段字节数（LENGTH_FIELD 生效）。SOS V3.0 为 2。
     */
    private Integer lengthFieldLength = 2;

    /**
     * 长度修正值（LENGTH_FIELD 生效），补偿长度字段之后、数据体之外的尾字节。SOS V3.0 为 5（CRC2+帧尾3）。
     */
    private Integer lengthAdjustment = 5;

    /**
     * 输出前剥离的字节数（LENGTH_FIELD 生效），0 表示向下游传递完整帧。
     */
    private Integer initialBytesToStrip = 0;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(Integer bossThreads) {
        this.bossThreads = bossThreads;
    }

    public Integer getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(Integer workerThreads) {
        this.workerThreads = workerThreads;
    }

    public Integer getMaxFrameLength() {
        return maxFrameLength;
    }

    public void setMaxFrameLength(Integer maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    public FrameMode getFrameMode() {
        return frameMode;
    }

    public void setFrameMode(FrameMode frameMode) {
        this.frameMode = frameMode;
    }

    public Integer getLengthFieldOffset() {
        return lengthFieldOffset;
    }

    public void setLengthFieldOffset(Integer lengthFieldOffset) {
        this.lengthFieldOffset = lengthFieldOffset;
    }

    public Integer getLengthFieldLength() {
        return lengthFieldLength;
    }

    public void setLengthFieldLength(Integer lengthFieldLength) {
        this.lengthFieldLength = lengthFieldLength;
    }

    public Integer getLengthAdjustment() {
        return lengthAdjustment;
    }

    public void setLengthAdjustment(Integer lengthAdjustment) {
        this.lengthAdjustment = lengthAdjustment;
    }

    public Integer getInitialBytesToStrip() {
        return initialBytesToStrip;
    }

    public void setInitialBytesToStrip(Integer initialBytesToStrip) {
        this.initialBytesToStrip = initialBytesToStrip;
    }

    /**
     * 帧切分模式。
     */
    public enum FrameMode {
        /**
         * 基于换行分隔符，适用于文本协议。
         */
        DELIMITER,
        /**
         * 基于长度字段，适用于二进制协议（如 SOS V3.0）。
         */
        LENGTH_FIELD
    }
}
