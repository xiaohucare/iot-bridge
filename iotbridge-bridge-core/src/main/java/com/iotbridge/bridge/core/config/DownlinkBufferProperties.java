package com.iotbridge.bridge.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下行待发缓冲配置，前缀 {@code iotbridge.downlink}。
 *
 * <p>默认关闭，保持向后兼容；开启后，下行指令命中不到设备会话时按 {@code deviceId} 暂存原始指令，
 * 待设备重新上线（有上行到达）时由补发服务重新编码并按序补发。</p>
 *
 * <p>边界语义：{@code maxPendingPerDevice <= 0} 视为非法，启动时告警并按最小值 1 兜底；
 * {@code pendingTtlMs <= 0} 表示暂存指令不过期；{@code maxPendingDevices <= 0} 视为非法，
 * 启动时告警并按最小值 1 兜底（限制同时暂存的设备数量，防止外部 deviceId 输入撑爆内存）。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@ConfigurationProperties(prefix = "iotbridge.downlink")
public class DownlinkBufferProperties {

    /**
     * 是否开启离线暂存重投。默认 {@code false}（未开启时行为与旧版一致：离线直接丢弃并告警）。
     */
    private boolean bufferEnabled = false;

    /**
     * 单设备最大暂存指令数，超出时丢弃最旧指令（有界队列，防止单设备内存无限增长）。
     */
    private int maxPendingPerDevice = 100;

    /**
     * 暂存指令存活时间（毫秒）；补发时丢弃超期指令。{@code <=0} 表示不过期。
     */
    private long pendingTtlMs = 300000L;

    /**
     * 同时暂存的最大设备数量；超限时为新设备淘汰「最陈旧」设备队列，防止 deviceId 无界增长耗尽内存。
     */
    private int maxPendingDevices = 10000;

    /**
     * 补发线程数：补发任务在独立线程池执行（不占 Netty IO 线程与 MQ 消费线程），
     * 同会话仍由分段锁串行，不同设备可并行补发，避免个别慢设备拖住其他设备的补发。
     */
    private int reflowThreads = 4;

    /**
     * 补发线程池的任务队列容量；满了直接拒绝并计数告警（形成背压，避免无界堆积）。
     */
    private int reflowQueueCapacity = 1024;

    /**
     * 是否开启下行投递幂等去重：同一 {@code (protocolType, deviceId, requestId)} 在 TTL 内
     * <b>只投递一次</b>（仅对「确认送达」或「已暂存」的指令记账），用于抑制 MQ 重投与 REST 重试
     * 造成的重复执行。
     */
    private boolean idempotencyEnabled = true;

    /**
     * 幂等去重记录存活时间（毫秒）。{@code <=0} 表示不过期（不推荐，内存只能靠容量上限约束）。
     */
    private long idempotencyTtlMs = 600000L;

    /**
     * 幂等去重记录的最大条数；超限先清理过期项，仍超限则拒绝新记账并告警。
     */
    private int idempotencyMaxEntries = 100000;

    public boolean isBufferEnabled() {
        return bufferEnabled;
    }

    public void setBufferEnabled(boolean bufferEnabled) {
        this.bufferEnabled = bufferEnabled;
    }

    public int getMaxPendingPerDevice() {
        return maxPendingPerDevice;
    }

    public void setMaxPendingPerDevice(int maxPendingPerDevice) {
        this.maxPendingPerDevice = maxPendingPerDevice;
    }

    public long getPendingTtlMs() {
        return pendingTtlMs;
    }

    public void setPendingTtlMs(long pendingTtlMs) {
        this.pendingTtlMs = pendingTtlMs;
    }

    public int getMaxPendingDevices() {
        return maxPendingDevices;
    }

    public void setMaxPendingDevices(int maxPendingDevices) {
        this.maxPendingDevices = maxPendingDevices;
    }

    public int getReflowThreads() {
        return reflowThreads;
    }

    public void setReflowThreads(int reflowThreads) {
        this.reflowThreads = reflowThreads;
    }

    public int getReflowQueueCapacity() {
        return reflowQueueCapacity;
    }

    public void setReflowQueueCapacity(int reflowQueueCapacity) {
        this.reflowQueueCapacity = reflowQueueCapacity;
    }

    public boolean isIdempotencyEnabled() {
        return idempotencyEnabled;
    }

    public void setIdempotencyEnabled(boolean idempotencyEnabled) {
        this.idempotencyEnabled = idempotencyEnabled;
    }

    public long getIdempotencyTtlMs() {
        return idempotencyTtlMs;
    }

    public void setIdempotencyTtlMs(long idempotencyTtlMs) {
        this.idempotencyTtlMs = idempotencyTtlMs;
    }

    public int getIdempotencyMaxEntries() {
        return idempotencyMaxEntries;
    }

    public void setIdempotencyMaxEntries(int idempotencyMaxEntries) {
        this.idempotencyMaxEntries = idempotencyMaxEntries;
    }
}
