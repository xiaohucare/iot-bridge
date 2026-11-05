package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.bridge.core.reflow.DownlinkReflowService;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DownlinkDispatcher} 设备离线暂存行为测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class DownlinkDispatcherTest {

    private static final String PT = "sos-c02";

    private DownlinkBuffer buffer(boolean enabled) {
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(enabled);
        properties.setMaxPendingPerDevice(100);
        properties.setPendingTtlMs(0);
        return new DownlinkBuffer(properties);
    }

    private DownlinkDispatcher dispatcher(ProtocolCodecRegistry registry, ChannelManager channelManager,
                                          DownlinkBuffer buffer) {
        DownlinkReflowService reflowService = new DownlinkReflowService(buffer, channelManager, registry);
        return new DownlinkDispatcher(registry, channelManager, buffer, reflowService);
    }

    private ProtocolCodec codecOffline() {
        ProtocolCodec codec = mock(ProtocolCodec.class);
        // 会话/缓冲以编解码器具象协议为键，需桩定 protocolType 与上行注册一致
        when(codec.protocolType()).thenReturn(PT);
        when(codec.encode(any())).thenReturn(new byte[]{1, 2, 3});
        return codec;
    }

    private ProtocolCodecRegistry registryReturning(ProtocolCodec codec) {
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.resolve(any())).thenReturn(codec);
        return registry;
    }

    @Test
    void offlineDownlinkIsBufferedWhenEnabled() {
        ProtocolCodec codec = codecOffline();
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);
        DownlinkBuffer buffer = buffer(true);

        dispatcher(registryReturning(codec), channelManager, buffer)
                .handle(new DownlinkCommand("dev1", PT, "timeCalib", null, "req1"));

        assertEquals(1, buffer.pendingSize(PT, "dev1"));
    }

    @Test
    void offlineDownlinkDroppedWhenDisabled() {
        ProtocolCodec codec = codecOffline();
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);
        DownlinkBuffer buffer = buffer(false);

        dispatcher(registryReturning(codec), channelManager, buffer)
                .handle(new DownlinkCommand("dev1", PT, "timeCalib", null, "req1"));

        assertEquals(0, buffer.pendingSize(PT, "dev1"));
    }

    @Test
    void onlineDownlinkNotBuffered() {
        ProtocolCodec codec = codecOffline();
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
        DownlinkBuffer buffer = buffer(true);

        dispatcher(registryReturning(codec), channelManager, buffer)
                .handle(new DownlinkCommand("dev1", PT, "timeCalib", null, "req1"));

        assertEquals(0, buffer.pendingSize(PT, "dev1"));
    }

    @Test
    void offlineThenOnlineDoubleCheckReflowsImmediately() {
        ProtocolCodec codec = codecOffline();
        ChannelManager channelManager = mock(ChannelManager.class);
        // handle 首次发送失败（离线），随后补发重新发送成功
        when(channelManager.sendToDevice(anyString(), anyString(), any())).thenReturn(Boolean.FALSE, Boolean.TRUE);
        // 暂存后双检发现设备此刻已在线，触发立即补发
        when(channelManager.isOnline(anyString(), anyString())).thenReturn(Boolean.TRUE);
        DownlinkBuffer buffer = buffer(true);
        ProtocolCodecRegistry registry = registryReturning(codec);

        dispatcher(registry, channelManager, buffer)
                .handle(new DownlinkCommand("dev1", PT, "timeCalib", null, "req1"));

        assertEquals(0, buffer.pendingSize(PT, "dev1"));
    }
}
