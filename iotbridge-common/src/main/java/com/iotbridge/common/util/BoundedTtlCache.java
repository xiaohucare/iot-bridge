package com.iotbridge.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界 TTL 缓存：按写入时间过期，条目数超上限时先清理过期项，仍超限则拒绝新写入。
 *
 * <p>用于「短期去重」类场景（下行幂等、REST 幂等键记忆）：</p>
 * <ul>
 *   <li>{@code ttlMs <= 0} 表示<b>永不过期</b>（与项目内其它 TTL 配置语义一致）；</li>
 *   <li>容量上限优先通过清理过期项腾挪；清理后仍达到上限则<b>拒绝本次写入并告警</b>，
 *       宁可漏掉一次去重也不让内存无界增长。拒绝计数可通过 {@link #rejectedWrites()} 观测。</li>
 * </ul>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 承载数据，容量判断为弱一致（并发下可能瞬时略超上限一两条，
 * 随后由下一次写入或定时清理收敛）。</p>
 *
 * @param <V> 值类型
 * @author iotbridge
 * @date 2026-11-14
 */
public final class BoundedTtlCache<V> {

    private static final Logger log = LoggerFactory.getLogger(BoundedTtlCache.class);

    /**
     * 过期时间（毫秒），{@code <=0} 表示永不过期。
     */
    private final long ttlMs;

    /**
     * 条目数上限，{@code <=0} 视为 1。
     */
    private final int maxEntries;

    /**
     * key -> 值；过期时间由单独的写入时间表维护，避免为每个值构造包装对象。
     */
    private final Map<String, V> values = new ConcurrentHashMap<>();

    /**
     * key -> 写入时间（毫秒）。
     */
    private final Map<String, Long> writtenAt = new ConcurrentHashMap<>();

    /**
     * 因容量上限被拒绝的写入次数。
     */
    private long rejectedWrites;

    public BoundedTtlCache(long ttlMs, int maxEntries) {
        this.ttlMs = ttlMs;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * 取未过期的值。
     *
     * @param key 键
     * @return 值；不存在或已过期返回 {@code null}
     */
    public V getFresh(String key) {
        if (key == null) {
            return null;
        }
        V value = values.get(key);
        if (value == null) {
            return null;
        }
        if (isExpired(key)) {
            remove(key);
            return null;
        }
        return value;
    }

    /**
     * 判断是否存在未过期的值。
     *
     * @param key 键
     * @return 存在返回 {@code true}
     */
    public boolean containsFresh(String key) {
        return getFresh(key) != null;
    }

    /**
     * 写入（覆盖同键旧值与旧时间戳）。容量不足且清理无效时拒绝写入。
     *
     * @param key   键，{@code null} 直接忽略
     * @param value 值，{@code null} 直接忽略
     * @return 写入成功返回 {@code true}；因容量上限被拒返回 {@code false}
     */
    public boolean put(String key, V value) {
        if (key == null || value == null) {
            return false;
        }
        if (!values.containsKey(key) && values.size() >= maxEntries) {
            purgeExpired();
            if (values.size() >= maxEntries) {
                rejectedWrites++;
                if (rejectedWrites == 1 || rejectedWrites % 1000 == 0) {
                    log.warn("TTL 缓存达到上限，拒绝写入 key={} 上限={} 累计拒绝={}", key, maxEntries, rejectedWrites);
                }
                return false;
            }
        }
        long now = System.currentTimeMillis();
        writtenAt.put(key, now);
        values.put(key, value);
        return true;
    }

    /**
     * 清理全部过期条目。
     *
     * @return 本次清理的条目数
     */
    public int purgeExpired() {
        if (ttlMs <= 0) {
            return 0;
        }
        int removed = 0;
        for (String key : values.keySet()) {
            if (isExpired(key) && remove(key)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 当前条目数（含尚未清理的过期条目，弱一致视图）。
     *
     * @return 条目数
     */
    public int size() {
        return values.size();
    }

    /**
     * 累计因容量上限被拒绝的写入次数。
     *
     * @return 拒绝次数
     */
    public long rejectedWrites() {
        return rejectedWrites;
    }

    /**
     * 判断条目是否过期。
     */
    private boolean isExpired(String key) {
        if (ttlMs <= 0) {
            return false;
        }
        Long at = writtenAt.get(key);
        return at != null && System.currentTimeMillis() - at > ttlMs;
    }

    /**
     * 删除条目（值与时间戳一并删除）。
     *
     * @param key 键
     * @return 值存在并被删除返回 {@code true}
     */
    private boolean remove(String key) {
        writtenAt.remove(key);
        return values.remove(key) != null;
    }
}
