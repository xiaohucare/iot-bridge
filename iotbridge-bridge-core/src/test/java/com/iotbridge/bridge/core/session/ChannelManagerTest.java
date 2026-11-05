package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @Test
    void snapshotOnlineDevicesShouldExcludeInactive() {
        ChannelManager manager = new ChannelManager();
        Channel active = newChannel("chan-a");
        Channel inactive = newChannel("chan-b");
        when(inactive.isActive()).thenReturn(Boolean.FALSE);

        manager.register("devA", active);
        manager.register("devB", inactive);

        Map<String, String> snapshot = manager.snapshotOnlineDevices();
        assertTrue(snapshot.containsKey("devA"));
        assertFalse(snapshot.containsKey("devB"));
        assertEquals(1, snapshot.size());
    }

    @Test
    void countActiveShouldExcludeInactiveButOnlineCountKeepsAll() {
        ChannelManager manager = new ChannelManager();
        Channel active = newChannel("chan-c");
        Channel inactive = newChannel("chan-d");
        when(inactive.isActive()).thenReturn(Boolean.FALSE);

        manager.register("devC", active);
        manager.register("devD", inactive);

        assertEquals(1, manager.countActive());
        // onlineCount 为登记会话数（含尚未清理的死连接），与 countActive 口径区分
        assertEquals(2, manager.onlineCount());
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
