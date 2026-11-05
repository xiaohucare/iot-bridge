package com.iotbridge.spi.handler;

import com.iotbridge.spi.model.DownlinkCommand;

/**
 * 下行指令派发回调接口。
 *
 * <p>由 mq 模块的下行消费者调用，实现方（bridge-core）负责选择协议编码并写入设备通道。
 * 通过接口置于 spi 层，使 mq 无需反向依赖 bridge-core。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public interface DownlinkDispatchHandler {

    /**
     * 处理一条来自消息中间件的下行指令。
     *
     * @param command 下行指令，不可为 {@code null}
     */
    void handle(DownlinkCommand command);
}
