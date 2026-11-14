package com.iotbridge.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoundedTtlCache} 行为测试：读写、TTL 过期、容量上限拒绝。
 *
 * @author iotbridge
 * @date 2026-11-14
 */
class BoundedTtlCacheTest {

    @Test
    void putThenGetReturnsValue() {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(0L, 10);

        assertTrue(cache.put("k", "v"));
        assertEquals("v", cache.getFresh("k"));
        assertTrue(cache.containsFresh("k"));
        assertEquals(1, cache.size());
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(30L, 10);
        cache.put("k", "v");

        Thread.sleep(60L);

        assertNull(cache.getFresh("k"));
        assertFalse(cache.containsFresh("k"));
    }

    @Test
    void nonPositiveTtlNeverExpires() throws InterruptedException {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(0L, 10);
        cache.put("k", "v");

        Thread.sleep(30L);

        assertEquals("v", cache.getFresh("k"));
    }

    @Test
    void writeRejectedWhenCapacityReachedAndNothingExpired() {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(0L, 2);
        assertTrue(cache.put("a", "1"));
        assertTrue(cache.put("b", "2"));

        assertFalse(cache.put("c", "3"));
        assertEquals(1L, cache.rejectedWrites());
        assertEquals(2, cache.size());
    }

    @Test
    void expiredEntriesArePurgedToMakeRoom() throws InterruptedException {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(30L, 2);
        cache.put("a", "1");
        cache.put("b", "2");

        Thread.sleep(60L);

        // 容量已满但旧条目已过期：先清理再写入，应成功
        assertTrue(cache.put("c", "3"));
        assertEquals(1, cache.size());
    }

    @Test
    void overwriteExistingKeyDoesNotConsumeCapacity() {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(0L, 1);
        assertTrue(cache.put("a", "1"));
        assertTrue(cache.put("a", "2"));

        assertEquals("2", cache.getFresh("a"));
        assertEquals(0L, cache.rejectedWrites());
    }

    @Test
    void nullKeyOrValueIsIgnored() {
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(0L, 10);

        assertFalse(cache.put(null, "v"));
        assertFalse(cache.put("k", null));
        assertNull(cache.getFresh(null));
        assertEquals(0, cache.size());
    }
}
