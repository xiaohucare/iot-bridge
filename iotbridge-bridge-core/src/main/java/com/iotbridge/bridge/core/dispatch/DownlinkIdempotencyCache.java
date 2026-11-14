package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.common.util.BoundedTtlCache;
import org.springframework.stereotype.Component;

/**
 * 下行投递幂等去重：同一 {@code (protocolType, deviceId, requestId)} 在 TTL 内只允许投递一次。
 *
 * <p>解决「MQ 至少一次投递 / REST 客户端重试 / 补发」三类重复来源造成的设备重复执行。</p>
 *
 * <p>只对<b>已确认送达</b>的指令记账：</p>
 * <ul>
 *   <li>「未送达」不记账，否则设备上线后的补发会被误判为重复而丢弃；</li>
 *   <li>「状态未知」不记账，首次投递仍有设备应答/对账兜底；</li>
 *   <li>补发成功、首投成功都会记账，因此之后到达的同 key 指令会被直接跳过。</li>
 * </ul>
 *
 * <p>约束：{@code requestId} 为空时不做去重（旧指令可能没有该字段），日志中会提示调用方补齐。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
@Component
public class DownlinkIdempotencyCache {

    /**
     * 缓存键分隔符：使用不可见控制字符，避免与 deviceId / protocolType / requestId 自身内容冲突。
     */
    private static final char KEY_SEPARATOR = '\u0001';

    /**
     * 是否启用去重。
     */
    private final boolean enabled;

    /**
     * 去重记录存储（值为固定占位，仅关心「是否存在」）。
     */
    private final BoundedTtlCache<Boolean> cache;

    public DownlinkIdempotencyCache(DownlinkBufferProperties properties) {
        DownlinkBufferProperties effective = properties == null ? new DownlinkBufferProperties() : properties;
        this.enabled = effective.isIdempotencyEnabled();
        this.cache = new BoundedTtlCache<>(effective.getIdempotencyTtlMs(), effective.getIdempotencyMaxEntries());
    }

    /**
     * 判断该指令是否已经成功投递过（重复投递应被跳过）。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @param requestId    指令请求号
     * @return 已送达过返回 {@code true}
     */
    public boolean isDuplicate(String protocolType, String deviceId, String requestId) {
        if (!enabled || isBlank(requestId)) {
            return false;
        }
        return cache.containsFresh(key(protocolType, deviceId, requestId));
    }

    /**
     * 记录一条「已确认送达」的指令。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @param requestId    指令请求号；为空时不记账
     */
    public void markDelivered(String protocolType, String deviceId, String requestId) {
        if (!enabled || isBlank(requestId)) {
            return;
        }
        cache.put(key(protocolType, deviceId, requestId), Boolean.TRUE);
    }

    /**
     * 当前去重记录条数（含未清理的过期项）。
     *
     * @return 记录条数
     */
    public int size() {
        return cache.size();
    }

    /**
     * 累计因容量上限被拒绝记账的次数。
     *
     * @return 拒绝次数
     */
    public long rejectedWrites() {
        return cache.rejectedWrites();
    }

    /**
     * 拼装去重键；协议类型为空时与 {@link com.iotbridge.spi.session.SessionKey} 保持一致的归一化处理。
     */
    private String key(String protocolType, String deviceId, String requestId) {
        String effectiveProtocol = protocolType == null ? "" : protocolType;
        return effectiveProtocol + KEY_SEPARATOR + deviceId + KEY_SEPARATOR + requestId;
    }

    /**
     * 空串/空白判定。
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
