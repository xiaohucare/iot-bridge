package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.spi.session.SessionKey;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelManager} 协议+设备会话注册、下线与隔离测试。
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
        // sendToDevice 会在非 EventLoop 线程等待写完成：桩定 EventLoop 与立即成功的写 future
        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(Boolean.FALSE);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.writeAndFlush(any())).thenAnswer(invocation ->
                new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE).setSuccess());
        return channel;
    }

    @Test
    void registerThenOnlineAndSend() {
        ChannelManager manager = new ChannelManager();
        Channel channel = newChannel("chan-1");

        manager.register("sos-c02", "dev001", channel);

        assertTrue(manager.isOnline("sos-c02", "dev001"));
        assertEquals(1, manager.onlineCount());
        boolean delivered = manager.sendToDevice("sos-c02", "dev001", "hi".getBytes(StandardCharsets.UTF_8));
        assertTrue(delivered);
        verify(channel).writeAndFlush(any());
    }

    @Test
    void unbindByChannelShouldMarkOffline() {
        ChannelManager manager = new ChannelManager();
        Channel channel = newChannel("chan-2");
        manager.register("sos-c02", "dev002", channel);

        manager.unbindByChannel(channel);

        assertFalse(manager.isOnline("sos-c02", "dev002"));
        assertEquals(0, manager.onlineCount());
    }

    @Test
    void sendToDeviceOfflineShouldReturnFalse() {
        ChannelManager manager = new ChannelManager();

        assertFalse(manager.sendToDevice("sos-c02", "ghost", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reRegisterSameDeviceClosesOldChannelAndKeepsNewSession() {
        ChannelManager manager = new ChannelManager();
        Channel old = newChannel("chan-old");
        Channel current = newChannel("chan-new");

        manager.register("sos-c02", "devR", old);
        manager.register("sos-c02", "devR", current);

        // 重复上线：新会话生效，旧连接被主动关闭（防僵尸连接）
        assertTrue(manager.isOnline("sos-c02", "devR"));
        assertEquals(1, manager.onlineCount());
        verify(old).close();
        // 旧连接随后的断开事件不得误清新会话
        manager.unbindByChannel(old);
        assertTrue(manager.isOnline("sos-c02", "devR"));
    }

    @Test
    void isDeviceOnlineUsesIndexAcrossProtocolsAndCleansUpOnUnbind() {
        ChannelManager manager = new ChannelManager();
        Channel sos = newChannel("chan-i1");
        Channel demo = newChannel("chan-i2");

        manager.register("sos-c02", "devI", sos);
        manager.register("demo-text", "devI", demo);
        assertTrue(manager.isDeviceOnline("devI"));
        assertFalse(manager.isDeviceOnline("ghost"));

        manager.unbindByChannel(sos);
        manager.unbindByChannel(demo);
        assertFalse(manager.isDeviceOnline("devI"));
    }

    @Test
    void sameDeviceIdAcrossProtocolsAreIsolated() {
        ChannelManager manager = new ChannelManager();
        Channel sosChannel = newChannel("chan-sos");
        Channel demoChannel = newChannel("chan-demo");

        // 不同协议、相同 deviceId 各自独立登记，互不覆盖
        manager.register("sos-c02", "devX", sosChannel);
        manager.register("demo-text", "devX", demoChannel);

        assertTrue(manager.isOnline("sos-c02", "devX"));
        assertTrue(manager.isOnline("demo-text", "devX"));
        assertEquals(2, manager.onlineCount());

        // 断开 SOS 会话仅影响该协议，demo-text 仍在线
        manager.unbindByChannel(sosChannel);
        assertFalse(manager.isOnline("sos-c02", "devX"));
        assertTrue(manager.isOnline("demo-text", "devX"));
        // 跨协议聚合：任一协议在线即算在线
        assertTrue(manager.isDeviceOnline("devX"));
    }

    @Test
    void snapshotOnlineDevicesShouldExcludeInactive() {
        ChannelManager manager = new ChannelManager();
        Channel active = newChannel("chan-a");
        Channel inactive = newChannel("chan-b");
        when(inactive.isActive()).thenReturn(Boolean.FALSE);

        manager.register("sos-c02", "devA", active);
        manager.register("demo-text", "devB", inactive);

        Map<SessionKey, String> snapshot = manager.snapshotOnlineDevices();
        assertTrue(snapshot.containsKey(SessionKey.of("sos-c02", "devA")));
        assertFalse(snapshot.containsKey(SessionKey.of("demo-text", "devB")));
        assertEquals(1, snapshot.size());
    }

    @Test
    void countActiveShouldExcludeInactiveButOnlineCountKeepsAll() {
        ChannelManager manager = new ChannelManager();
        Channel active = newChannel("chan-c");
        Channel inactive = newChannel("chan-d");
        when(inactive.isActive()).thenReturn(Boolean.FALSE);

        manager.register("sos-c02", "devC", active);
        manager.register("sos-c02", "devD", inactive);

        assertEquals(1, manager.countActive());
        // onlineCount 为登记会话数（含尚未清理的死连接），与 countActive 口径区分
        assertEquals(2, manager.onlineCount());
    }
}
