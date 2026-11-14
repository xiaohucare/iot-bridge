package com.iotbridge.bridge.core.reflow;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.session.DeliveryResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DownlinkReflowService} 补发行为测试：按序发送、发送失败退回剩余帧、编码失败跳过、关闭时不动作。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class DownlinkReflowServiceTest {

    private static final String PT = "sos-c02";

    private DownlinkBuffer enabledBuffer(int max) {
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(true);
        properties.setMaxPendingPerDevice(max);
        properties.setPendingTtlMs(0);
        return new DownlinkBuffer(properties);
    }

    private PendingFrame frame(String deviceId, String command) {
        return new PendingFrame(new DownlinkCommand(deviceId, PT, command, null, "req-" + command),
                System.currentTimeMillis());
    }

    @Test
    void reflowSendsAllFramesInOrderWhenChannelActive() {
        DownlinkBuffer buffer = enabledBuffer(10);
        buffer.offer(PT, "d", frame("d", "a"));
        buffer.offer(PT, "d", frame("d", "b"));

        ProtocolCodec codec = mock(ProtocolCodec.class);
        when(codec.encode(any())).thenReturn(new byte[]{1});
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(DeliveryResult.DELIVERED);

        new DownlinkReflowService(buffer, channelManager, registry, Runnable::run).reflow(PT, "d");

        verify(channelManager, times(2)).sendToDevice(eq(PT), eq("d"), any());
        assertEquals(0, buffer.pendingSize(PT, "d"));
    }

    @Test
    void reflowReturnsRemainingFramesOnSendFailure() {
        DownlinkBuffer buffer = enabledBuffer(10);
        buffer.offer(PT, "d", frame("d", "a"));
        buffer.offer(PT, "d", frame("d", "b"));

        ProtocolCodec codec = mock(ProtocolCodec.class);
        when(codec.encode(any())).thenReturn(new byte[]{1});
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);
        // 第一帧成功，第二帧失败 -> 仅剩余未发送的第二帧退回缓冲
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(DeliveryResult.DELIVERED, DeliveryResult.NOT_DELIVERED);

        new DownlinkReflowService(buffer, channelManager, registry, Runnable::run).reflow(PT, "d");

        verify(channelManager, times(2)).sendToDevice(eq(PT), eq("d"), any());
        assertEquals(1, buffer.pendingSize(PT, "d"));
    }

    @Test
    void reflowSkipsFrameOnEncodeFailureAndEmptiesBuffer() {
        DownlinkBuffer buffer = enabledBuffer(10);
        buffer.offer(PT, "d", frame("d", "a"));
        buffer.offer(PT, "d", frame("d", "b"));

        ProtocolCodec codec = mock(ProtocolCodec.class);
        // 编码确定性失败：跳过该帧，不重排、不发送
        when(codec.encode(any())).thenThrow(new RuntimeException("encode error"));
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);

        new DownlinkReflowService(buffer, channelManager, registry, Runnable::run).reflow(PT, "d");

        verify(channelManager, never()).sendToDevice(anyString(), anyString(), any());
        assertEquals(0, buffer.pendingSize(PT, "d"));
    }

    @Test
    void reflowDoesNothingWhenBufferDisabled() {
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(false);
        DownlinkBuffer buffer = new DownlinkBuffer(properties);
        ChannelManager channelManager = mock(ChannelManager.class);
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);

        new DownlinkReflowService(buffer, channelManager, registry, Runnable::run).reflow(PT, "d");

        verify(channelManager, never()).sendToDevice(anyString(), anyString(), any());
    }

    @Test
    void reflowIgnoresNullDeviceId() {
        DownlinkBuffer buffer = enabledBuffer(10);
        ChannelManager channelManager = mock(ChannelManager.class);
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);

        new DownlinkReflowService(buffer, channelManager, registry, Runnable::run).reflow(PT, null);

        verify(channelManager, never()).sendToDevice(anyString(), anyString(), any());
    }

    @Test
    void unknownDeliveryKeepsOnlyFramesAfterCurrentOne() {
        DownlinkBuffer buffer = enabledBuffer(10);
        buffer.offer(PT, "d", frame("d", "a"));
        buffer.offer(PT, "d", frame("d", "b"));
        buffer.offer(PT, "d", frame("d", "c"));

        ProtocolCodec codec = mock(ProtocolCodec.class);
        when(codec.encode(any())).thenReturn(new byte[]{1});
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);
        // 第 1 帧送达；第 2 帧写等待超时（可能已写出，不得重投）
        when(channelManager.sendToDevice(anyString(), anyString(), any()))
                .thenReturn(DeliveryResult.DELIVERED, DeliveryResult.UNKNOWN);

        new DownlinkReflowService(buffer, channelManager, registry, Runnable::run).reflow(PT, "d");

        // 只退回第 3 帧：当前帧按「至多一次」处理，避免设备重复执行
        assertEquals(1, buffer.pendingSize(PT, "d"));
        verify(channelManager, times(2)).sendToDevice(eq(PT), eq("d"), any());
    }

    @Test
    void reflowWithoutPendingDoesNotSubmitTask() {
        DownlinkBuffer buffer = enabledBuffer(10);
        ChannelManager channelManager = mock(ChannelManager.class);
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        AtomicInteger submitted = new AtomicInteger();

        // 上行每帧都会触发补发：没有待发指令时不应提交任务，避免心跳把补发队列塞满
        new DownlinkReflowService(buffer, channelManager, registry,
                (Executor) runnable -> submitted.incrementAndGet()).reflow(PT, "d");

        assertEquals(0, submitted.get());
    }
}
