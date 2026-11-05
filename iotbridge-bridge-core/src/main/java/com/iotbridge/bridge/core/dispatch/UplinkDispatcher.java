package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.config.UplinkProperties;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.bridge.core.reflow.DownlinkReflowService;
import com.iotbridge.mq.api.UplinkProducer;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.UplinkMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上行分发器：设备帧 -> 协议解码 -> 会话绑定 -> Kafka 投递。
 *
 * <p>实现接入层 {@link UplinkFrameListener}，由 Netty 入站处理器回调。按接入端口绑定的
 * 协议类型（{@link UplinkFrameEvent#getProtocolType()}，为空则回退注册表默认协议）解码，
 * 解码得到 deviceId 后登记 {@code deviceId<->Channel}，随后投递上行消息。</p>
 *
 * <p><b>不占用 Netty IO 线程</b>：回调只做一次「按远端地址取分片 + 提交任务」，解码、会话登记与
 * Kafka 投递都在独立的单线程分片执行。分片内串行保证同一连接的上行帧有序，分片间并行互不阻塞；
 * 即使 Kafka 发送因 broker 不可用而阻塞（{@code max.block.ms}），也只影响该分片上的连接。
 * 队列有界，溢出时丢弃并计数告警，避免无界积压耗尽内存。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class UplinkDispatcher implements UplinkFrameListener {

    private static final Logger log = LoggerFactory.getLogger(UplinkDispatcher.class);

    /**
     * 队列溢出告警的采样间隔（每丢弃若干个帧记录一条 WARN，避免日志放大）。
     */
    private static final long DROP_LOG_SAMPLE = 1024L;

    private final ProtocolCodecRegistry codecRegistry;
    private final ChannelManager channelManager;
    private final UplinkProducer uplinkProducer;
    private final DownlinkReflowService downlinkReflowService;

    /**
     * 上行工作分片，每个分片一个单线程执行器；为空表示未启用（仅测试使用同步执行器场景）。
     */
    private final Executor[] workers;
    private final boolean ownsWorkers;
    private final AtomicLong droppedFrames = new AtomicLong();

    @Autowired
    public UplinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                            UplinkProducer uplinkProducer, DownlinkReflowService downlinkReflowService,
                            UplinkProperties uplinkProperties) {
        this(codecRegistry, channelManager, uplinkProducer, downlinkReflowService,
                newWorkers(uplinkProperties), true);
    }

    /**
     * 以单一执行器构造（测试注入同步执行器时使用）。
     *
     * @param codecRegistry        协议注册表
     * @param channelManager       会话管理
     * @param uplinkProducer       上行生产者
     * @param downlinkReflowService 补发服务
     * @param executor             上行任务执行器
     */
    public UplinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                            UplinkProducer uplinkProducer, DownlinkReflowService downlinkReflowService,
                            Executor executor) {
        this(codecRegistry, channelManager, uplinkProducer, downlinkReflowService,
                new Executor[]{executor}, false);
    }

    private UplinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                             UplinkProducer uplinkProducer, DownlinkReflowService downlinkReflowService,
                             Executor[] workers, boolean ownsWorkers) {
        this.codecRegistry = codecRegistry;
        this.channelManager = channelManager;
        this.uplinkProducer = uplinkProducer;
        this.downlinkReflowService = downlinkReflowService;
        this.workers = workers;
        this.ownsWorkers = ownsWorkers;
    }

    /**
     * 构建单线程分片池：每片一条线程 + 有界队列，拒绝策略交由调用侧丢弃并告警。
     *
     * @param properties 上行线程池配置
     * @return 分片执行器数组
     */
    private static Executor[] newWorkers(UplinkProperties properties) {
        int shards = Math.max(1, properties.getWorkerThreads());
        int perShardCapacity = Math.max(1, properties.getQueueCapacity() / shards);
        Executor[] executors = new Executor[shards];
        for (int i = 0; i < shards; i++) {
            String name = "uplink-worker-" + i;
            executors[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(perShardCapacity), runnable -> {
                        Thread thread = new Thread(runnable, name);
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        }
        log.info("上行处理分片已启用 分片数={} 单分片队列容量={}", shards, perShardCapacity);
        return executors;
    }

    @Override
    public void onUplinkFrame(UplinkFrameEvent event) {
        Executor worker = workers[stripeOf(event.getRemoteAddress())];
        try {
            worker.execute(() -> process(event));
        } catch (RejectedExecutionException ex) {
            long dropped = droppedFrames.incrementAndGet();
            if (dropped == 1 || dropped % DROP_LOG_SAMPLE == 0) {
                log.warn("上行处理队列已满，丢弃该帧 remote={} 累计丢弃={}", event.getRemoteAddress(), dropped);
            }
        }
    }

    /**
     * 按远端地址哈希选取分片，保证同一连接固定在同一线程上顺序处理。
     *
     * @param remoteAddress 远端地址
     * @return 分片下标
     */
    private int stripeOf(String remoteAddress) {
        if (remoteAddress == null) {
            return 0;
        }
        return Math.floorMod(remoteAddress.hashCode(), workers.length);
    }

    /**
     * 在工作线程内完成解码、会话登记、上行投递与上线补发；异常仅在当前帧内隔离。
     *
     * @param event 上行帧事件
     */
    private void process(UplinkFrameEvent event) {
        try {
            ProtocolCodec codec = codecRegistry.resolve(event.getProtocolType());
            UplinkMessage message = codec.decode(event.getFrame());
            // 绑定协议+设备与通道，供下行按 (protocol,deviceId) 寻址（不同协议相同设备号不互覆）
            channelManager.register(message.getProtocolType(), message.getDeviceId(), event.getChannel());
            if (log.isDebugEnabled()) {
                log.debug("上行分发 deviceId={} protocol={} payload={}", message.getDeviceId(),
                        message.getProtocolType(), message.getPayload());
            }
            uplinkProducer.send(message);
            // 设备（重新）上线，补发此前因离线暂存的下行指令；置于上行投递之后且内部隔离异常，不影响主链路
            downlinkReflowService.reflow(message.getProtocolType(), message.getDeviceId());
        } catch (RuntimeException ex) {
            // 单帧处理异常不中断连接，也不影响该分片后续帧
            log.error("上行帧处理异常 remote={} frameLen={}", event.getRemoteAddress(), event.getFrame().length, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!ownsWorkers) {
            return;
        }
        for (Executor worker : workers) {
            if (worker instanceof ThreadPoolExecutor executor) {
                executor.shutdownNow();
            }
        }
    }
}
