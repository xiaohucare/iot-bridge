package com.iotbridge.bridge.core.reflow;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.session.SessionKey;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 下行补发服务：把某设备暂存的待重投指令按序重新编码并写入通道。
 *
 * <p><b>独立补发线程池</b>：补发任务提交到专用线程池执行（不占用 Netty IO 线程与 MQ 消费线程），
 * 内部含逐帧重编码 + 写通道，离线积压大时也不会阻塞 EventLoop 拖慢其他设备；不同设备可并行补发，
 * 避免个别慢设备拖住其他设备。</p>
 *
 * <p>按 {@code SessionKey} 串行化（固定分段锁，内存恒定、无需回收），保证「排空 -> 逐条发送」原子执行，
 * 消除补发与实时下发、以及多次补发交错的乱序与滞留。补发时以<b>当前</b>指令重新编码（而非缓存字节帧），规避
 * {@code timeCalib} 等含时间戳指令在离线期间失效；发送失败立即中断并把剩余帧按原序退回缓冲，等待
 * 下一次上线补发，杜绝 drain 出的帧静默丢失。异常在内部隔离，不影响调用方主链路。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkReflowService {

    private static final Logger log = LoggerFactory.getLogger(DownlinkReflowService.class);

    /**
     * 关闭执行器时等待在途任务收尾的最长时间。
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    /**
     * 补发串行锁分段数：按会话键哈希到固定数量的锁上，内存恒定、无需像 per-key 锁那样回收，
     * 少量无关会话共享一把锁对吞吐影响可忽略。
     */
    private static final int LOCK_STRIPES = 256;

    private final DownlinkBuffer downlinkBuffer;
    private final ChannelManager channelManager;
    private final ProtocolCodecRegistry codecRegistry;
    private final Executor reflowExecutor;
    private final boolean ownsExecutor;
    private final Object[] reflowLocks = createLocks();

    @Autowired
    public DownlinkReflowService(DownlinkBuffer downlinkBuffer, ChannelManager channelManager,
                                 ProtocolCodecRegistry codecRegistry, DownlinkBufferProperties properties) {
        this(downlinkBuffer, channelManager, codecRegistry, newReflowExecutor(properties.getReflowThreads()));
    }

    /**
     * 以外部执行器构造（供测试注入同步执行器等场景）。
     *
     * @param downlinkBuffer 下行缓冲
     * @param channelManager 会话管理
     * @param codecRegistry  协议注册表
     * @param reflowExecutor 补发任务执行器
     */
    public DownlinkReflowService(DownlinkBuffer downlinkBuffer, ChannelManager channelManager,
                                 ProtocolCodecRegistry codecRegistry, Executor reflowExecutor) {
        this.downlinkBuffer = downlinkBuffer;
        this.channelManager = channelManager;
        this.codecRegistry = codecRegistry;
        this.reflowExecutor = reflowExecutor;
        this.ownsExecutor = reflowExecutor instanceof ExecutorService;
    }

    /**
     * 初始化分段锁数组。
     *
     * @return 锁数组
     */
    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /**
     * 构建补发线程池：多线程并行处理不同设备的补发，同会话顺序由分段锁保证。
     *
     * @param threads 线程数
     * @return 补发执行器
     */
    private static ExecutorService newReflowExecutor(int threads) {
        int poolSize = Math.max(1, threads);
        return Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "downlink-reflow");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 排空并补发指定协议下某设备暂存的下行指令。缓冲未开启或设备标识为空时直接返回。
     *
     * <p>任务提交到独立补发线程异步执行（不在调用线程上做重编码与写通道）；同会话任务经分段锁互斥；
     * 异常被捕获隔离，不外泄到上行投递等主链路。</p>
     *
     * @param protocolType 协议类型（与设备上线/入队时一致的具象协议）
     * @param deviceId     设备标识
     */
    public void reflow(String protocolType, String deviceId) {
        if (!downlinkBuffer.isEnabled() || deviceId == null) {
            return;
        }
        reflowExecutor.execute(() -> {
            SessionKey key = SessionKey.of(protocolType, deviceId);
            Object lock = reflowLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
            synchronized (lock) {
                try {
                    drainAndSend(protocolType, deviceId);
                } catch (RuntimeException ex) {
                    log.error("下行补发异常 protocol={} deviceId={}", protocolType, deviceId, ex);
                }
            }
        });
    }

    /**
     * 取出全部暂存指令并按序重新编码发送；发送失败时把剩余帧（含当前帧）退回缓冲，编码失败则跳过该帧。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     */
    private void drainAndSend(String protocolType, String deviceId) {
        List<PendingFrame> pending = downlinkBuffer.drain(protocolType, deviceId);
        if (pending.isEmpty()) {
            return;
        }
        log.info("设备上线补发暂存下行 protocol={} deviceId={} 数量={}", protocolType, deviceId, pending.size());
        for (int i = 0; i < pending.size(); i++) {
            PendingFrame frame = pending.get(i);
            byte[] encoded;
            try {
                ProtocolCodec codec = codecRegistry.resolve(frame.getProtocolType());
                encoded = codec.encode(frame.getDownlinkCommand());
            } catch (RuntimeException ex) {
                // 编码失败为确定性错误，重排无意义且可能死循环，记录后跳过该帧
                log.error("补发编码失败，丢弃该帧 deviceId={} command={} requestId={}", deviceId,
                        frame.getCommand(), frame.getRequestId(), ex);
                continue;
            }
            if (!channelManager.sendToDevice(protocolType, deviceId, encoded)) {
                // 通道中途失效：把尚未发送成功的剩余帧按原序退回缓冲，等待下次上线
                reEnqueue(protocolType, deviceId, pending, i);
                log.warn("补发中断，剩余 {} 帧已退回缓冲 protocol={} deviceId={}", pending.size() - i, protocolType,
                        deviceId);
                return;
            }
        }
    }

    /**
     * 将 {@code fromIndex}（含）之后的剩余帧按原序退回缓冲。仅退回未成功发送的帧，避免重复下发。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @param pending      本轮排空出的帧列表
     * @param fromIndex    首个未成功发送帧的下标
     */
    private void reEnqueue(String protocolType, String deviceId, List<PendingFrame> pending, int fromIndex) {
        for (int j = fromIndex; j < pending.size(); j++) {
            downlinkBuffer.offer(protocolType, deviceId, pending.get(j));
        }
    }

    /**
     * 释放补发执行器，等待在途补发任务收尾。
     */
    @PreDestroy
    public void shutdown() {
        if (!ownsExecutor) {
            return;
        }
        ((ExecutorService) reflowExecutor).shutdown();
        try {
            if (!((ExecutorService) reflowExecutor).awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ((ExecutorService) reflowExecutor).shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ((ExecutorService) reflowExecutor).shutdownNow();
        }
    }
}
