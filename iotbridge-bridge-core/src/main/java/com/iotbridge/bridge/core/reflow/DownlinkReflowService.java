package com.iotbridge.bridge.core.reflow;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.bridge.core.dispatch.DownlinkIdempotencyCache;
import com.iotbridge.bridge.core.metrics.BridgeMetrics;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.session.DeliveryResult;
import com.iotbridge.spi.session.SessionKey;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下行补发服务：把某设备暂存的待重投指令按序重新编码并写入通道。
 *
 * <p><b>独立补发线程池</b>：补发任务提交到专用线程池执行（不占用 Netty IO 线程与 MQ 消费线程），
 * 内部含逐帧重编码 + 写通道，离线积压大时也不会阻塞 EventLoop 拖慢其他设备；不同设备可并行补发，
 * 避免个别慢设备拖住其他设备。</p>
 *
 * <p><b>背压与去重</b>：</p>
 * <ul>
 *   <li>任务队列<b>有界</b>，满了直接拒绝并计数（不阻塞调用方，也不让任务无界堆积）；</li>
 *   <li>提交前先判该会话是否有待补发指令，避免空任务（心跳帧会持续触发本方法）；</li>
 *   <li>同一会话的补发任务以 in-flight 标记去重，重复提交直接返回。</li>
 * </ul>
 *
 * <p>按 {@code SessionKey} 串行化（固定分段锁，内存恒定、无需回收），保证「排空 -> 逐条发送」原子执行，
 * 消除补发与实时下发、以及多次补发交错的乱序与滞留。补发时以<b>当前</b>指令重新编码（而非缓存字节帧），规避
 * {@code timeCalib} 等含时间戳指令在离线期间失效；发送失败立即中断并把剩余帧按原序退回缓冲，等待
 * 下一次上线补发，杜绝 drain 出的帧静默丢失。异常在内部隔离，不影响调用方主链路。</p>
 *
 * <p><b>三态语义</b>：确认「未送达」才退回缓冲；「状态未知」（写等待超时）把<b>当前帧</b>视为可能已发出、
 * 只退回其后的帧，避免重复执行。</p>
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
    private final DownlinkIdempotencyCache idempotencyCache;
    private final BridgeMetrics metrics;
    private final Executor reflowExecutor;
    private final boolean ownsExecutor;
    private final Object[] reflowLocks = createLocks();

    /**
     * 正在补发/排队中的会话集合：用于抑制同一会话的重复任务提交。
     */
    private final Set<SessionKey> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 因任务队列已满被拒绝的提交次数（用于限流告警）。
     */
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * Spring 注入用构造。
     *
     * @param downlinkBuffer   下行缓冲
     * @param channelManager   会话管理
     * @param codecRegistry    协议注册表
     * @param properties       下行缓冲/补发配置
     * @param idempotencyCache 幂等去重
     * @param metrics          指标
     */
    @Autowired
    public DownlinkReflowService(DownlinkBuffer downlinkBuffer, ChannelManager channelManager,
                                 ProtocolCodecRegistry codecRegistry, DownlinkBufferProperties properties,
                                 DownlinkIdempotencyCache idempotencyCache, BridgeMetrics metrics) {
        this(downlinkBuffer, channelManager, codecRegistry,
                newReflowExecutor(properties.getReflowThreads(), properties.getReflowQueueCapacity()),
                idempotencyCache, metrics);
    }

    /**
     * 以配置构造（默认幂等缓存、空操作指标），保持既有调用方兼容。
     *
     * @param downlinkBuffer 下行缓冲
     * @param channelManager 会话管理
     * @param codecRegistry  协议注册表
     * @param properties     配置
     */
    public DownlinkReflowService(DownlinkBuffer downlinkBuffer, ChannelManager channelManager,
                                 ProtocolCodecRegistry codecRegistry, DownlinkBufferProperties properties) {
        this(downlinkBuffer, channelManager, codecRegistry,
                newReflowExecutor(properties.getReflowThreads(), properties.getReflowQueueCapacity()),
                new DownlinkIdempotencyCache(properties), BridgeMetrics.NOOP);
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
        this(downlinkBuffer, channelManager, codecRegistry, reflowExecutor,
                new DownlinkIdempotencyCache(new DownlinkBufferProperties()), BridgeMetrics.NOOP);
    }

    /**
     * 全参构造。
     *
     * @param downlinkBuffer   下行缓冲
     * @param channelManager   会话管理
     * @param codecRegistry    协议注册表
     * @param reflowExecutor   补发任务执行器
     * @param idempotencyCache 幂等去重
     * @param metrics          指标
     */
    public DownlinkReflowService(DownlinkBuffer downlinkBuffer, ChannelManager channelManager,
                                 ProtocolCodecRegistry codecRegistry, Executor reflowExecutor,
                                 DownlinkIdempotencyCache idempotencyCache, BridgeMetrics metrics) {
        this.downlinkBuffer = downlinkBuffer;
        this.channelManager = channelManager;
        this.codecRegistry = codecRegistry;
        this.reflowExecutor = reflowExecutor;
        this.idempotencyCache = idempotencyCache;
        this.metrics = metrics;
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
     * <p>使用<b>有界队列</b> + 拒绝策略形成背压：队列满时立刻拒绝并计数，而不是无界堆积任务，
     * 否则设备集中重连时会因任务排满内存而拖垮进程。</p>
     *
     * @param threads       线程数
     * @param queueCapacity 任务队列容量
     * @return 补发执行器
     */
    private static ExecutorService newReflowExecutor(int threads, int queueCapacity) {
        int poolSize = Math.max(1, threads);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "downlink-reflow");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 排空并补发指定协议下某设备暂存的下行指令。缓冲未开启、设备标识为空或无待发指令时直接返回。
     *
     * <p>任务提交到独立补发线程异步执行（不在调用线程上做重编码与写通道）；同会话任务经分段锁互斥、
     * 并以 in-flight 标记去重；队列满时拒绝并计数；异常被捕获隔离，不外泄到上行投递等主链路。</p>
     *
     * @param protocolType 协议类型（与设备上线/入队时一致的具象协议）
     * @param deviceId     设备标识
     */
    public void reflow(String protocolType, String deviceId) {
        if (!downlinkBuffer.isEnabled() || deviceId == null) {
            return;
        }
        // 上行每帧都会触发本方法：没有待补发指令直接返回，避免心跳持续产生空任务
        if (downlinkBuffer.pendingSize(protocolType, deviceId) <= 0) {
            return;
        }
        SessionKey key = SessionKey.of(protocolType, deviceId);
        if (!inFlight.add(key)) {
            // 该会话已有补发任务在排队/执行中，重复提交无收益
            return;
        }
        try {
            reflowExecutor.execute(() -> runReflow(key));
        } catch (RejectedExecutionException ex) {
            inFlight.remove(key);
            long rejected = rejectedCount.incrementAndGet();
            metrics.reflow(BridgeMetrics.REFLOW_REJECTED);
            if (rejected == 1 || rejected % 1000 == 0) {
                log.warn("补发任务队列已满，本次补发已拒绝 protocol={} deviceId={} 累计拒绝={}", protocolType,
                        deviceId, rejected);
            }
        }
    }

    /**
     * 在补发线程内执行一次会话补发：分段锁串行化 + 异常隔离 + MDC。
     *
     * @param key 会话键
     */
    private void runReflow(SessionKey key) {
        MDC.put("deviceId", key.getDeviceId() == null ? "" : key.getDeviceId());
        MDC.put("protocolType", key.getProtocolType() == null ? "" : key.getProtocolType());
        Object lock = reflowLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
        try {
            synchronized (lock) {
                drainAndSend(key.getProtocolType(), key.getDeviceId());
            }
        } catch (RuntimeException ex) {
            log.error("下行补发异常 protocol={} deviceId={}", key.getProtocolType(), key.getDeviceId(), ex);
        } finally {
            inFlight.remove(key);
            MDC.remove("deviceId");
            MDC.remove("protocolType");
        }
    }

    /**
     * 取出全部暂存指令并按序重新编码发送。
     *
     * <p>编码失败跳过该帧；确认未送达则把该帧及其后帧按原序退回缓冲；状态未知则只退回其后帧。</p>
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
            if (idempotencyCache.isDuplicate(protocolType, deviceId, frame.getRequestId())) {
                // 该请求号此前已确认送达（如 MQ 重投再入队），补发前直接丢弃，避免设备重复执行
                log.warn("补发跳过已送达指令 deviceId={} command={} requestId={}", deviceId, frame.getCommand(),
                        frame.getRequestId());
                metrics.reflow(BridgeMetrics.REFLOW_SKIPPED);
                continue;
            }
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
            DeliveryResult result = channelManager.sendToDevice(protocolType, deviceId, encoded);
            if (result == DeliveryResult.DELIVERED) {
                idempotencyCache.markDelivered(protocolType, deviceId, frame.getRequestId());
                metrics.reflow(BridgeMetrics.REFLOW_SENT);
                continue;
            }
            if (result == DeliveryResult.UNKNOWN) {
                // 当前帧可能已写出：只退其后帧，当前帧按「至多一次」处理，避免设备重复执行
                metrics.reflow(BridgeMetrics.REFLOW_UNKNOWN);
                reEnqueue(protocolType, deviceId, pending, i + 1);
                log.warn("补发写入状态未知，当帧不重投、其余 {} 帧已退回缓冲 protocol={} deviceId={}",
                        Math.max(0, pending.size() - i - 1), protocolType, deviceId);
                return;
            }
            // 通道中途失效：把尚未发送成功的剩余帧（含当前帧）按原序退回缓冲，等待下次上线
            metrics.reflow(BridgeMetrics.REFLOW_FAILED);
            reEnqueue(protocolType, deviceId, pending, i);
            log.warn("补发中断，剩余 {} 帧已退回缓冲 protocol={} deviceId={}", pending.size() - i, protocolType,
                    deviceId);
            return;
        }
    }

    /**
     * 将 {@code fromIndex}（含）之后的剩余帧按原序退回缓冲。仅退回未成功发送的帧，避免重复下发。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @param pending      本轮排空出的帧列表
     * @param fromIndex    首个需要退回的帧下标
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
