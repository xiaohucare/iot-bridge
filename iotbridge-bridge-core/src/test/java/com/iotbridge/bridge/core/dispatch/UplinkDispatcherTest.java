package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.bridge.core.reflow.DownlinkReflowService;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.mq.api.UplinkProducer;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UplinkDispatcher} 上线补发暂存下行测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class UplinkDispatcherTest {

    private DownlinkBuffer buffer(boolean enabled) {
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(enabled);
        properties.setPendingTtlMs(0);
        return new DownlinkBuffer(properties);
    }

    private UplinkFrameEvent eventOf(Channel channel) {
        return new UplinkFrameEvent(channel, new byte[]{1}, "127.0.0.1:1");
    }

    @Test
    void decodesWithPortBoundProtocolType() {
        // 接入端口绑定协议时，应按该协议类型而非默认协议解码
        ProtocolCodec codec = mock(ProtocolCodec.class);
        when(codec.decode(any())).thenReturn(new UplinkMessage("dev1", "demo-text", new HashMap<>(), 1L));
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);
        UplinkProducer producer = mock(UplinkProducer.class);
        Channel channel = mock(Channel.class);
        DownlinkReflowService reflowService = new DownlinkReflowService(buffer(false), channelManager, registry,
                Runnable::run);
        UplinkDispatcher dispatcher = new UplinkDispatcher(registry, channelManager, producer, reflowService,
                Runnable::run);

        UplinkFrameEvent event = new UplinkFrameEvent(channel, new byte[]{1}, "127.0.0.1:1", "demo-text");
        dispatcher.onUplinkFrame(event);

        verify(registry).resolve(eq("demo-text"));
        verify(channelManager).register(eq("demo-text"), eq("dev1"), eq(channel));
        verify(producer).send(any());
    }

    @Test
    void pendingDownlinkReflowedWhenDeviceComesOnline() {
        DownlinkBuffer buffer = buffer(true);
        DownlinkCommand pendingCommand = new DownlinkCommand("dev1", "sos-c02", "timeCalib", null, "req1");
        buffer.offer("sos-c02", "dev1", new PendingFrame(pendingCommand, System.currentTimeMillis()));

        byte[] encoded = new byte[]{9, 8, 7};
        ProtocolCodec codec = mock(ProtocolCodec.class);
        when(codec.decode(any())).thenReturn(new UplinkMessage("dev1", "sos-c02", new HashMap<>(), 1L));
        when(codec.encode(any())).thenReturn(encoded);
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.getDefault()).thenReturn(codec);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
        UplinkProducer producer = mock(UplinkProducer.class);
        Channel channel = mock(Channel.class);

        DownlinkReflowService reflowService = new DownlinkReflowService(buffer, channelManager, registry, Runnable::run);
        UplinkDispatcher dispatcher = new UplinkDispatcher(registry, channelManager, producer, reflowService,
                Runnable::run);
        dispatcher.onUplinkFrame(eventOf(channel));

        verify(channelManager).register(eq("sos-c02"), eq("dev1"), eq(channel));
        // 补发时以原始指令重新编码后发送，按协议+设备寻址
        verify(channelManager).sendToDevice("sos-c02", "dev1", encoded);
        assertEquals(0, buffer.pendingSize("sos-c02", "dev1"));
    }

    @Test
    void noReflowWhenBufferDisabled() {
        DownlinkBuffer buffer = buffer(false);
        // 关闭时 offer 被忽略，缓冲区始终为空
        buffer.offer("sos-c02", "dev1", new PendingFrame(
                new DownlinkCommand("dev1", "sos-c02", "cmd", null, "req"), System.currentTimeMillis()));

        ProtocolCodec codec = mock(ProtocolCodec.class);
        when(codec.decode(any())).thenReturn(new UplinkMessage("dev1", "sos-c02", new HashMap<>(), 1L));
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.getDefault()).thenReturn(codec);
        when(registry.resolve(any())).thenReturn(codec);
        ChannelManager channelManager = mock(ChannelManager.class);
        UplinkProducer producer = mock(UplinkProducer.class);
        Channel channel = mock(Channel.class);

        DownlinkReflowService reflowService = new DownlinkReflowService(buffer, channelManager, registry, Runnable::run);
        UplinkDispatcher dispatcher = new UplinkDispatcher(registry, channelManager, producer, reflowService,
                Runnable::run);
        dispatcher.onUplinkFrame(eventOf(channel));

        verify(channelManager).register(eq("sos-c02"), eq("dev1"), eq(channel));
        verify(channelManager, never()).sendToDevice(anyString(), anyString(), any());
        assertEquals(0, buffer.pendingSize("sos-c02", "dev1"));
    }
}
