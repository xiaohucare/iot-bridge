package com.iotbridge.access.mqtt.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqttTopicMatcher} 主题过滤器匹配测试。
 *
 * @author iotbridge
 * @date 2026-11-06
 */
class MqttTopicMatcherTest {

    @Test
    void exactTopicMatches() {
        assertTrue(MqttTopicMatcher.matches("iotbridge/downlink/dev1", "iotbridge/downlink/dev1"));
        assertFalse(MqttTopicMatcher.matches("iotbridge/downlink/dev1", "iotbridge/downlink/dev2"));
    }

    @Test
    void singleLevelWildcardMatchesOneLevelOnly() {
        assertTrue(MqttTopicMatcher.matches("iotbridge/downlink/+", "iotbridge/downlink/dev1"));
        assertFalse(MqttTopicMatcher.matches("iotbridge/downlink/+", "iotbridge/downlink/dev1/extra"));
        assertFalse(MqttTopicMatcher.matches("iotbridge/downlink/+", "iotbridge/downlink"));
    }

    @Test
    void multiLevelWildcardMatchesRemainingLevels() {
        assertTrue(MqttTopicMatcher.matches("iotbridge/#", "iotbridge/downlink/dev1"));
        assertTrue(MqttTopicMatcher.matches("#", "iotbridge/downlink/dev1"));
        // 多层级通配含 0 层：a/# 也匹配 a
        assertTrue(MqttTopicMatcher.matches("a/#", "a"));
        assertFalse(MqttTopicMatcher.matches("iotbridge/+/dev1/#", "iotbridge/downlink/dev2"));
    }

    @Test
    void invalidFiltersDoNotMatch() {
        // # 仅可作为末层
        assertFalse(MqttTopicMatcher.matches("iotbridge/#/dev1", "iotbridge/x/dev1"));
        assertFalse(MqttTopicMatcher.matches("", "iotbridge/downlink/dev1"));
        assertFalse(MqttTopicMatcher.matches(null, "iotbridge/downlink/dev1"));
    }

    @Test
    void matchesAnyUsesFirstMatchingFilter() {
        List<String> filters = List.of("iotbridge/downlink/dev1", "other/+");
        assertTrue(MqttTopicMatcher.matchesAny(filters, "iotbridge/downlink/dev1"));
        assertFalse(MqttTopicMatcher.matchesAny(filters, "iotbridge/downlink/dev2"));
        assertFalse(MqttTopicMatcher.matchesAny(List.of(), "iotbridge/downlink/dev1"));
    }

    @Test
    void subscriptionWildcardAcceptsDeviceDownlinkTopic() {
        assertTrue(MqttTopicMatcher.matchesAny(List.of("iotbridge/downlink/#"), "iotbridge/downlink/dev1"));
        assertTrue(MqttTopicMatcher.matchesAny(List.of("iotbridge/+/dev1"), "iotbridge/downlink/dev1"));
    }
}
