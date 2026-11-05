package com.iotbridge.bridge.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上行处理线程池配置，前缀 {@code iotbridge.uplink}。
 *
 * <p>设备帧的解码、会话登记与 Kafka 投递全部在这些工作线程执行，不占用 Netty IO 线程：
 * 即使 Kafka broker 不可用导致发送阻塞，也只影响对应分片，不会拖垮整条 EventLoop。</p>
 *
 * <p>按「远端地址哈希」把连接固定到某个单线程工作线程，保证同一连接的上行帧顺序处理；
 * 各分片之间并行，互不阻塞。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@ConfigurationProperties(prefix = "iotbridge.uplink")
public class UplinkProperties {

    /**
     * 上行工作线程数（分片数），每个分片为单线程，保证同连接帧有序。
     */
    private int workerThreads = 4;

    /**
     * 上行待处理帧的总队列容量上限（按分片均分）；满了直接丢弃并告警，形成背压保护。
     */
    private int queueCapacity = 10000;

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
