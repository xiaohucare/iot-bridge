package com.iotbridge.bridge.core.buffer;

import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.spi.model.DownlinkCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DownlinkBuffer} 暂存/补发/容量/TTL 行为测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class DownlinkBufferTest {

    private DownlinkBuffer buffer(boolean enabled, int max, long ttlMs) {
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(enabled);
        properties.setMaxPendingPerDevice(max);
        properties.setPendingTtlMs(ttlMs);
        return new DownlinkBuffer(properties);
    }

    private PendingFrame frame(String command, long enqueuedAt) {
        DownlinkCommand downlinkCommand = new DownlinkCommand("d", null, command, null, "req-" + command);
        return new PendingFrame(downlinkCommand, enqueuedAt);
    }

    @Test
    void offerDrainKeepsOrderAndClearsQueue() {
        DownlinkBuffer buffer = buffer(true, 10, 0);
        buffer.offer("d1", frame("a", 1));
        buffer.offer("d1", frame("b", 2));

        assertEquals(2, buffer.pendingSize("d1"));
        List<PendingFrame> drained = buffer.drain("d1");
        assertEquals(2, drained.size());
        assertEquals("a", drained.get(0).getCommand());
        assertEquals("b", drained.get(1).getCommand());
        assertEquals(0, buffer.pendingSize("d1"));
    }

    @Test
    void capacityDropsOldestFrame() {
        DownlinkBuffer buffer = buffer(true, 2, 0);
        buffer.offer("d", frame("1", 1));
        buffer.offer("d", frame("2", 2));
        buffer.offer("d", frame("3", 3));

        List<PendingFrame> drained = buffer.drain("d");
        assertEquals(2, drained.size());
        assertEquals("2", drained.get(0).getCommand());
        assertEquals("3", drained.get(1).getCommand());
    }

    @Test
    void ttlExpiresOldFramesOnDrain() {
        long now = System.currentTimeMillis();
        DownlinkBuffer buffer = buffer(true, 10, 1000);
        buffer.offer("d", frame("old", now - 5000));
        buffer.offer("d", frame("new", now));

        List<PendingFrame> drained = buffer.drain("d");
        assertEquals(1, drained.size());
        assertEquals("new", drained.get(0).getCommand());
    }

    @Test
    void disabledBufferIgnoresOffer() {
        DownlinkBuffer buffer = buffer(false, 10, 0);
        buffer.offer("d", frame("x", 1));

        assertEquals(0, buffer.pendingSize("d"));
        assertTrue(buffer.drain("d").isEmpty());
    }

    @Test
    void totalPendingAndClear() {
        DownlinkBuffer buffer = buffer(true, 10, 0);
        buffer.offer("d1", frame("a", 1));
        buffer.offer("d2", frame("b", 2));
        buffer.offer("d2", frame("c", 3));

        assertEquals(3, buffer.totalPending());
        buffer.clear();
        assertEquals(0, buffer.totalPending());
    }

    @Test
    void drainHandlesNullAndUnknownDeviceGracefully() {
        DownlinkBuffer buffer = buffer(true, 10, 0);

        assertTrue(buffer.drain(null).isEmpty());
        assertTrue(buffer.drain("ghost").isEmpty());
        assertEquals(0, buffer.pendingSize(null));
    }

    @Test
    void deviceCapacityEvictsStalestQueueOnNewDevice() {
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(true);
        properties.setMaxPendingPerDevice(10);
        properties.setMaxPendingDevices(2);
        properties.setPendingTtlMs(0);
        DownlinkBuffer buffer = new DownlinkBuffer(properties);

        buffer.offer("d1", frame("x", 10));
        buffer.offer("d2", frame("y", 20));
        // 新设备 d3 入队前触发上限淘汰：队首最陈旧的是 d1(enqueuedAt=10)
        buffer.offer("d3", frame("z", 30));

        assertEquals(2, buffer.pendingDeviceCount());
        assertEquals(0, buffer.pendingSize("d1"));
        assertEquals(1, buffer.pendingSize("d2"));
        assertEquals(1, buffer.pendingSize("d3"));
    }
}
