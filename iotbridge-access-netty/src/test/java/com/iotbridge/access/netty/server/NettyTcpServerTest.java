package com.iotbridge.access.netty.server;

import com.iotbridge.access.netty.config.NettyProperties;
import com.iotbridge.access.netty.config.NettyProperties.FrameMode;
import com.iotbridge.access.netty.config.NettyProperties.Listener;
import com.iotbridge.access.netty.server.NettyTcpServer.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link NettyTcpServer#resolveEndpoints} 多监听解析与全局默认继承测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class NettyTcpServerTest {

    private NettyProperties globals() {
        NettyProperties props = new NettyProperties();
        props.setHost("0.0.0.0");
        props.setPort(9999);
        props.setFrameMode(FrameMode.LENGTH_FIELD);
        props.setMaxFrameLength(1024);
        props.setLengthFieldOffset(9);
        props.setLengthFieldLength(2);
        props.setLengthAdjustment(5);
        props.setInitialBytesToStrip(0);
        return props;
    }

    @Test
    void emptyListenersSynthesizeSingleGlobalEndpoint() {
        NettyProperties props = globals();
        props.setPort(9000);

        List<Endpoint> endpoints = NettyTcpServer.resolveEndpoints(props);

        assertEquals(1, endpoints.size());
        Endpoint endpoint = endpoints.get(0);
        assertEquals("default", endpoint.getName());
        assertEquals(9000, endpoint.getPort());
        assertNull(endpoint.getProtocolType());
        assertEquals(FrameMode.LENGTH_FIELD, endpoint.getFrameMode());
        assertEquals(1024, endpoint.getMaxFrameLength());
        assertEquals(9, endpoint.getLengthFieldOffset());
    }

    @Test
    void listenerInheritsGlobalDefaultsAndOverrides() {
        NettyProperties props = globals();
        Listener sos = new Listener();
        sos.setName("sos");
        sos.setPort(9000);
        sos.setProtocolType("sos-c02");
        Listener demo = new Listener();
        demo.setName("demo-text");
        demo.setPort(9001);
        demo.setProtocolType("demo-text");
        demo.setFrameMode(FrameMode.DELIMITER);
        demo.setMaxFrameLength(65536);
        props.setListeners(Arrays.asList(sos, demo));

        List<Endpoint> endpoints = NettyTcpServer.resolveEndpoints(props);

        assertEquals(2, endpoints.size());
        Endpoint sosEp = endpoints.get(0);
        assertEquals("sos", sosEp.getName());
        assertEquals(9000, sosEp.getPort());
        assertEquals("sos-c02", sosEp.getProtocolType());
        // 未填项继承全局 LENGTH_FIELD 参数
        assertEquals("0.0.0.0", sosEp.getHost());
        assertEquals(FrameMode.LENGTH_FIELD, sosEp.getFrameMode());
        assertEquals(1024, sosEp.getMaxFrameLength());
        assertEquals(5, sosEp.getLengthAdjustment());

        Endpoint demoEp = endpoints.get(1);
        assertEquals(9001, demoEp.getPort());
        assertEquals("demo-text", demoEp.getProtocolType());
        // 显式覆盖帧模式与最大帧长，其余继承全局 host
        assertEquals(FrameMode.DELIMITER, demoEp.getFrameMode());
        assertEquals(65536, demoEp.getMaxFrameLength());
        assertEquals("0.0.0.0", demoEp.getHost());
    }

    @Test
    void listenerWithoutPortFailsFast() {
        NettyProperties props = globals();
        Listener bad = new Listener();
        bad.setName("no-port");
        bad.setProtocolType("sos-c02");
        props.setListeners(Collections.singletonList(bad));

        assertThrows(IllegalStateException.class, () -> NettyTcpServer.resolveEndpoints(props));
    }

    @Test
    void lengthFieldWithoutRequiredParamsFailsFast() {
        // LENGTH_FIELD 模式下长度字段三参数为协议专属必填项，缺失即启动失败
        NettyProperties props = globals();
        props.setLengthFieldOffset(null);
        props.setLengthFieldLength(null);
        props.setLengthAdjustment(null);

        assertThrows(IllegalStateException.class, () -> NettyTcpServer.resolveEndpoints(props));
    }

    @Test
    void delimiterModeDoesNotRequireLengthFieldParams() {
        // 行分隔模式不依赖长度字段参数，缺省（null）应可正常解析
        NettyProperties props = globals();
        props.setFrameMode(FrameMode.DELIMITER);
        props.setLengthFieldOffset(null);
        props.setLengthFieldLength(null);
        props.setLengthAdjustment(null);
        props.setListeners(Collections.emptyList());

        List<Endpoint> endpoints = NettyTcpServer.resolveEndpoints(props);
        assertEquals(1, endpoints.size());
        assertEquals(FrameMode.DELIMITER, endpoints.get(0).getFrameMode());
    }
}
