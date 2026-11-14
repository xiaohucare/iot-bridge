package com.iotbridge.spi.session;

/**
 * 下行写入结果（三态语义）。
 *
 * <p>把「是否送达」从布尔升级为三态，解决「写等待超时返回失败，但数据其实已经写出」导致的
 * <b>重复下发</b>问题：超时属于 {@link #UNKNOWN}，调用方不应据此重投。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
public enum DeliveryResult {

    /**
     * 已确认写入成功（写 future 成功完成）。
     */
    DELIVERED,

    /**
     * 送达状态未知：写操作已在通道上提交，但等待完成超时 / 调用线程被中断。
     *
     * <p>数据<b>可能</b>已经送达设备。调用方不得重投（否则设备可能重复执行），
     * 应记录告警并计数，由设备应答或业务侧对账兜底。</p>
     */
    UNKNOWN,

    /**
     * 确定未送达：设备离线、通道不可写（出站缓冲超水位）、写 future 明确失败。
     *
     * <p>这是唯一可以安全重投（暂存补发）的状态。需要先关闭通道时（写 future 失败），
     * 也应按未送达处理以避免在坏连接上反复写入。</p>
     */
    NOT_DELIVERED;

    /**
     * 是否确定送达。
     *
     * @return {@link #DELIVERED} 返回 {@code true}
     */
    public boolean isDelivered() {
        return this == DELIVERED;
    }
}
