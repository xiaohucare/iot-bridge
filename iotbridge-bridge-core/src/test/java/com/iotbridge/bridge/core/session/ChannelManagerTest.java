package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelManager} 设备会话注册与下线测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class ChannelManagerTest {

    private Channel newChannel(String channelId) {
        Channel channel = mock(Channel.class);
        ChannelId id = mock(ChannelId.class);
        when(channel.id()).thenReturn(id);
        when(id.asLongText()).thenReturn(channelId);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
        when(channel.isActive()).thenReturn(Boolean.TRUE);
        return channel;
    }

    @Test
    void registerThenOnlineAndSend() {
        ChannelManager manager = new ChannelManager();
        Channel channel = newChannel("chan-1");

        manager.register("dev001", channel);

        assertTrue(manager.isOnline("dev001"));
        assertEquals(1, manager.onlineCount());
        boolean delivered = manager.sendToDevice("dev001", "hi\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(delivered);
        verify(channel).writeAndFlush(any());
    }

    @Test
    void unbindByChannelShouldMarkOffline() {
        ChannelManager manager = new ChannelManager();
        Channel channel = newChannel("chan-2");
        manager.register("dev002", channel);

        manager.unbindByChannel(channel);

        assertFalse(manager.isOnline("dev002"));
        assertEquals(0, manager.onlineCount());
    }

    @Test
    void sendToDeviceOfflineShouldReturnFalse() {
        ChannelManager manager = new ChannelManager();

        assertFalse(manager.sendToDevice("ghost", "x".getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
