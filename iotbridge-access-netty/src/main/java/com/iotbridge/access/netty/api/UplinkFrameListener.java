package com.iotbridge.access.netty.api;

/**
 * 上行帧监听回调接口。
 *
 * <p>由接入层在收到设备帧时回调，实现方为 bridge-core。通过接口反转，使 access-netty
 * 无需依赖 bridge-core。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public interface UplinkFrameListener {

    /**
     * 处理一条上行帧事件。
     *
     * @param event 上行帧事件
     */
    void onUplinkFrame(UplinkFrameEvent event);
}
