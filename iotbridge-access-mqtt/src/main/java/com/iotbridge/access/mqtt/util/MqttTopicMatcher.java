package com.iotbridge.access.mqtt.util;

import java.util.Collection;

/**
 * MQTT 主题过滤器匹配。
 *
 * <p>支持 {@code +}（匹配单层）与 {@code #}（匹配多层，仅可作为末层），用于判断设备是否订阅了
 * 下行主题；不实现系统主题（{@code $SYS/}）等扩展语义。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public final class MqttTopicMatcher {

    private MqttTopicMatcher() {
        // 工具类不允许实例化
    }

    /**
     * 判断主题过滤器是否匹配指定主题。
     *
     * @param filter 主题过滤器（可含 {@code +}/{@code #}）
     * @param topic  实际主题
     * @return 匹配返回 {@code true}
     */
    public static boolean matches(String filter, String topic) {
        if (filter == null || topic == null || filter.isEmpty() || topic.isEmpty()) {
            return false;
        }
        String[] filterLevels = filter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);
        int i = 0;
        for (; i < filterLevels.length; i++) {
            String level = filterLevels[i];
            if ("#".equals(level)) {
                // 多层级通配必须位于末尾（含 0 层，例如 a/# 也匹配 a）
                return i == filterLevels.length - 1;
            }
            if (i >= topicLevels.length) {
                return false;
            }
            if ("+".equals(level)) {
                continue;
            }
            if (!level.equals(topicLevels[i])) {
                return false;
            }
        }
        return i == topicLevels.length;
    }

    /**
     * 判断主题是否被任一订阅过滤器匹配。
     *
     * @param filters 订阅的过滤器集合
     * @param topic   实际主题
     * @return 任一匹配返回 {@code true}
     */
    public static boolean matchesAny(Collection<String> filters, String topic) {
        if (filters == null || filters.isEmpty()) {
            return false;
        }
        for (String filter : filters) {
            if (matches(filter, topic)) {
                return true;
            }
        }
        return false;
    }
}
