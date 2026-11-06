package com.iotbridge.access.mqtt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 接入配置，前缀 {@code iotbridge.mqtt}。
 *
 * <p>网关内置轻量 MQTT 3.1.1 服务端（Broker）：设备以 clientId 作为设备号接入，
 * PUBLISH 到上行主题即视为上行数据，下行指令由网关 PUBLISH 到该设备的主题。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@ConfigurationProperties(prefix = "iotbridge.mqtt")
public class MqttProperties {

    /**
     * 是否启用 MQTT 接入。
     */
    private Boolean enabled = Boolean.TRUE;

    /**
     * 监听地址。
     */
    private String host = "0.0.0.0";

    /**
     * 监听端口，标准 MQTT 端口为 1883（TLS 为 8883，需在网关前置 TLS）。
     */
    private Integer port = 1883;

    /**
     * boss 线程数。
     */
    private Integer bossThreads = 1;

    /**
     * worker 线程数，0 表示由 Netty 依 CPU 核数默认。
     */
    private Integer workerThreads = 0;

    /**
     * 该接入在会话/下行路由中使用的协议类型标识。
     */
    private String protocolType = "mqtt";

    /**
     * 单条 MQTT 消息最大字节数，防止异常大包耗尽内存。
     */
    private Integer maxMessageSize = 65536;

    /**
     * 上行主题前缀：设备 PUBLISH 到该前缀下（含前缀本身）的主题视为上行数据。
     */
    private String uplinkTopicPrefix = "iotbridge/uplink";

    /**
     * 下行主题前缀：网关向 {@code <前缀>/<clientId>} 发布下行指令。
     */
    private String downlinkTopicPrefix = "iotbridge/downlink";

    /**
     * 下行 PUBLISH 的 QoS（仅支持 0/1）。
     */
    private Integer qos = 0;

    /**
     * 访问用户名；为空表示允许匿名接入。
     */
    private String username = "";

    /**
     * 访问密码；配置用户名时生效。
     */
    private String password = "";

    /**
     * 客户端 CONNECT keepalive 为 0 时的兜底读空闲秒数（{@code <=0} 表示关闭检测）。
     * keepalive 非 0 时按 1.5 倍 keepalive 检测。
     */
    private Integer idleSeconds = 300;

    /**
     * 出站写缓冲高水位（字节），超过后 {@code isWritable()} 为假，下行按未送达处理。
     */
    private Integer writeBufferHighWaterMark = 64 * 1024;

    /**
     * 出站写缓冲低水位（字节）。
     */
    private Integer writeBufferLowWaterMark = 32 * 1024;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

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

    public String getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
    }

    public Integer getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(Integer maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public String getUplinkTopicPrefix() {
        return uplinkTopicPrefix;
    }

    public void setUplinkTopicPrefix(String uplinkTopicPrefix) {
        this.uplinkTopicPrefix = uplinkTopicPrefix;
    }

    public String getDownlinkTopicPrefix() {
        return downlinkTopicPrefix;
    }

    public void setDownlinkTopicPrefix(String downlinkTopicPrefix) {
        this.downlinkTopicPrefix = downlinkTopicPrefix;
    }

    public Integer getQos() {
        return qos;
    }

    public void setQos(Integer qos) {
        this.qos = qos;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getIdleSeconds() {
        return idleSeconds;
    }

    public void setIdleSeconds(Integer idleSeconds) {
        this.idleSeconds = idleSeconds;
    }

    public Integer getWriteBufferHighWaterMark() {
        return writeBufferHighWaterMark;
    }

    public void setWriteBufferHighWaterMark(Integer writeBufferHighWaterMark) {
        this.writeBufferHighWaterMark = writeBufferHighWaterMark;
    }

    public Integer getWriteBufferLowWaterMark() {
        return writeBufferLowWaterMark;
    }

    public void setWriteBufferLowWaterMark(Integer writeBufferLowWaterMark) {
        this.writeBufferLowWaterMark = writeBufferLowWaterMark;
    }

    /**
     * 是否配置了接入用户名（启用鉴权）。
     *
     * @return 配置了非空用户名返回 {@code true}
     */
    public boolean isAuthEnabled() {
        return username != null && !username.isEmpty();
    }
}
