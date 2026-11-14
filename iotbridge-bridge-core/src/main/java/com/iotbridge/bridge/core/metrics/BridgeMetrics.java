package com.iotbridge.bridge.core.metrics;

import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.spi.session.DeviceSessionQuery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 网关核心指标（Micrometer）。
 *
 * <p>指标目录（详见 {@code docs/monitoring/metrics.md}）：</p>
 * <ul>
 *   <li>{@code iotbridge_uplink_frames_total{protocol,result}}：上行帧处理结果；</li>
 *   <li>{@code iotbridge_uplink_dropped_total}：上行队列满被丢弃的帧数；</li>
 *   <li>{@code iotbridge_downlink_total{result}}：下行投递结果
 *       （delivered/unknown/buffered/dropped/duplicate/error）；</li>
 *   <li>{@code iotbridge_sessions_active}：当前在线会话数（gauge）；</li>
 *   <li>{@code iotbridge_downlink_buffer_pending}：暂存待补发指令总数（gauge）；</li>
 *   <li>{@code iotbridge_reflow_total{result}}：补发结果（sent/failed/unknown/rejected）；</li>
 *   <li>{@code iotbridge_security_auth_total{protocol,result}}：设备接入认证结果
 *       （allowed/notConfigured/unknownDevice/disabled/badCredential/networkDenied）。</li>
 * </ul>
 *
 * <p>无 {@link MeterRegistry} 时（单元测试、非 Spring 场景）全部方法降级为空操作，使用 {@link #NOOP}。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
@Component
public class BridgeMetrics {

    private static final Logger log = LoggerFactory.getLogger(BridgeMetrics.class);

    /**
     * 上行帧结果：处理成功。
     */
    public static final String UPLINK_OK = "ok";

    /**
     * 上行帧结果：解码/处理失败。
     */
    public static final String UPLINK_ERROR = "error";

    /**
     * 上行帧结果：无法识别设备号。
     */
    public static final String UPLINK_NO_DEVICE_ID = "noDeviceId";

    /**
     * 下行结果：确认送达。
     */
    public static final String DOWNLINK_DELIVERED = "delivered";

    /**
     * 下行结果：送达状态未知（不重投）。
     */
    public static final String DOWNLINK_UNKNOWN = "unknown";

    /**
     * 下行结果：已进入离线暂存。
     */
    public static final String DOWNLINK_BUFFERED = "buffered";

    /**
     * 下行结果：未送达且未开启暂存，直接丢弃。
     */
    public static final String DOWNLINK_DROPPED = "dropped";

    /**
     * 下行结果：命中幂等去重，跳过。
     */
    public static final String DOWNLINK_DUPLICATE = "duplicate";

    /**
     * 下行结果：编码/协议解析失败。
     */
    public static final String DOWNLINK_ERROR = "error";

    /**
     * 补发结果：发送成功。
     */
    public static final String REFLOW_SENT = "sent";

    /**
     * 补发结果：未送达，退回队列。
     */
    public static final String REFLOW_FAILED = "failed";

    /**
     * 补发结果：状态未知，不再重投当帧。
     */
    public static final String REFLOW_UNKNOWN = "unknown";

    /**
     * 补发结果：该请求号已送达过，补发时跳过。
     */
    public static final String REFLOW_SKIPPED = "skipped";

    /**
     * 补发结果：任务被有界队列拒绝。
     */
    public static final String REFLOW_REJECTED = "rejected";

    /**
     * 记录一次设备接入认证结果。
     *
     * @param protocolType 协议类型
     * @param result       认证结果标签，见 {@code com.iotbridge.spi.security.DeviceAuthResult#tag()}
     */
    public void securityAuth(String protocolType, String result) {
        if (registry == null) {
            return;
        }
        registry.counter("iotbridge.security.auth", "protocol", normalize(protocolType), "result", normalize(result))
                .increment();
    }

    /**
     * 空操作实例：所有方法不产生任何记录，供非 Spring 场景与单元测试使用。
     */
    public static final BridgeMetrics NOOP = new BridgeMetrics();

    /**
     * 注册表；为 {@code null} 时整个类降级为空操作。
     */
    private final MeterRegistry registry;

    /**
     * 上行丢帧计数（无标签）。
     */
    private final Counter uplinkDropped;

    /**
     * 构造空操作实例。
     */
    private BridgeMetrics() {
        this.registry = null;
        this.uplinkDropped = null;
    }

    /**
     * Spring 注入构造：允许运行环境没有 {@link MeterRegistry}（例如未引入
     * {@code spring-boot-starter-actuator} 的精简部署），此时指标降级为空操作并告警，
     * 不因“缺监控组件”直接拒绝启动。
     *
     * @param registryProvider 注册表提供者（可能为空）
     * @param sessions         会话查询（提供在线数）
     * @param buffer           下行暂存缓冲（提供待发量与设备数）
     */
    @Autowired
    public BridgeMetrics(ObjectProvider<MeterRegistry> registryProvider, DeviceSessionQuery sessions,
                         DownlinkBuffer buffer) {
        this(registryProvider.getIfAvailable(), sessions, buffer);
    }

    /**
     * 构造指标组件并注册在线会话、缓冲待发量两个 gauge。
     *
     * @param registry 注册表；为 {@code null} 时降级为空操作
     * @param sessions 会话查询（提供在线数）
     * @param buffer   下行暂存缓冲（提供待发量与设备数）
     */
    public BridgeMetrics(MeterRegistry registry, DeviceSessionQuery sessions, DownlinkBuffer buffer) {
        this.registry = registry;
        if (registry == null) {
            this.uplinkDropped = null;
            log.warn("未发现 MeterRegistry（通常缺少 spring-boot-starter-actuator），指标降级为空操作；"
                    + "如需监控请引入 actuator 并确认其在运行时 classpath 中");
            return;
        }
        this.uplinkDropped = Counter.builder("iotbridge.uplink.dropped")
                .description("上行队列满被丢弃的帧数")
                .register(registry);
        Gauge.builder("iotbridge.sessions.active", sessions, DeviceSessionQuery::onlineCount)
                .description("当前在线会话数")
                .register(registry);
        Gauge.builder("iotbridge.downlink.buffer.pending", buffer, DownlinkBuffer::totalPending)
                .description("暂存待补发指令总数")
                .register(registry);
        Gauge.builder("iotbridge.downlink.buffer.devices", buffer, DownlinkBuffer::pendingDeviceCount)
                .description("存在待补发指令的设备数")
                .register(registry);
        log.info("网关指标已注册：uplink/downlink/sessions/buffer/reflow/security");
    }

    /**
     * 记录一帧上行处理结果。
     *
     * @param protocolType 协议类型；为空时记为 {@code unknown}
     * @param result       结果标签，见 {@code UPLINK_*} 常量
     */
    public void uplinkFrame(String protocolType, String result) {
        if (registry == null) {
            return;
        }
        registry.counter("iotbridge.uplink.frames", "protocol", normalize(protocolType), "result", normalize(result))
                .increment();
    }

    /**
     * 记录一帧因为上行队列满而被丢弃。
     */
    public void uplinkDropped() {
        if (uplinkDropped != null) {
            uplinkDropped.increment();
        }
    }

    /**
     * 记录一次下行投递结果。
     *
     * @param result 结果标签，见 {@code DOWNLINK_*} 常量
     */
    public void downlink(String result) {
        if (registry == null) {
            return;
        }
        registry.counter("iotbridge.downlink", "result", normalize(result)).increment();
    }

    /**
     * 记录一次补发结果。
     *
     * @param result 结果标签，见 {@code REFLOW_*} 常量
     */
    public void reflow(String result) {
        if (registry == null) {
            return;
        }
        registry.counter("iotbridge.reflow", "result", normalize(result)).increment();
    }

    /**
     * 标签归一化：避免空标签值导致指标维度缺失。
     */
    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }
}
