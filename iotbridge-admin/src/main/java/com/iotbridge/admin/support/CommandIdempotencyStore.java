package com.iotbridge.admin.support;

import com.iotbridge.admin.config.AdminSecurityProperties;
import com.iotbridge.common.util.BoundedTtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * REST 下行指令的幂等键记忆。
 *
 * <p>客户端携带 {@code Idempotency-Key} 重复提交同一请求时，返回首次分配的 {@code requestId}
 * 且<b>不再重复投放</b>下行队列，避免超时重试造成设备重复执行。</p>
 *
 * <p>键按 {@code deviceId + 幂等键} 组装：同一幂等键用于不同设备视为不同请求。
 * 记忆在投放成功后才写入，投放失败（broker 未确认）不会被记忆，客户端可安全重试。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
@Component
public class CommandIdempotencyStore {

    /**
     * 键分隔符：不可见控制字符，避免与设备号自身内容冲突。
     */
    private static final char KEY_SEPARATOR = '\u0001';

    /**
     * 幂等键 -> 首次分配的 requestId。
     */
    private final BoundedTtlCache<String> cache;

    /**
     * 构造并读取记忆容量配置。
     *
     * @param properties 管理端安全/幂等配置
     */
    @Autowired
    public CommandIdempotencyStore(AdminSecurityProperties properties) {
        AdminSecurityProperties effective = properties == null ? new AdminSecurityProperties() : properties;
        this.cache = new BoundedTtlCache<>(effective.getIdempotencyTtlMs(), effective.getIdempotencyMaxEntries());
    }

    /**
     * 查询该幂等键此前是否已成功投放。
     *
     * @param deviceId       设备标识
     * @param idempotencyKey 客户端幂等键；为空返回 {@code null}
     * @return 已投放返回首次的 requestId，否则 {@code null}
     */
    public String find(String deviceId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        return cache.getFresh(deviceId + KEY_SEPARATOR + idempotencyKey);
    }

    /**
     * 记住一次成功投放。
     *
     * @param deviceId       设备标识
     * @param idempotencyKey 客户端幂等键
     * @param requestId      本次分配的请求号
     */
    public void remember(String deviceId, String idempotencyKey, String requestId) {
        if (idempotencyKey == null || requestId == null) {
            return;
        }
        cache.put(deviceId + KEY_SEPARATOR + idempotencyKey, requestId);
    }

    /**
     * 当前记忆条数（含未清理的过期项）。
     *
     * @return 条数
     */
    public int size() {
        return cache.size();
    }
}
