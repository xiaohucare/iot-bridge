package com.iotbridge.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;

import java.util.List;
import java.util.Map;

/**
 * Jackson 序列化 / 反序列化封装。
 *
 * <p>金额等精确数值统一走 {@code String}/{@code BigDecimal}，禁止 {@code double} 直接构造。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private JsonUtil() {
        // 工具类不允许实例化
    }

    /**
     * 对象序列化为 JSON 字符串。
     *
     * @param obj 待序列化对象，可为 {@code null}
     * @return JSON 字符串；入参为 {@code null} 时返回 {@code null}
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR, "对象序列化失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为指定类型对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标泛型
     * @return 反序列化结果
     */
    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR, "JSON 反序列化失败", e);
        }
    }

    /**
     * JSON 数组字符串反序列化为 List。
     *
     * @param json       JSON 数组字符串
     * @param elementClazz 元素类型
     * @param <T>        元素泛型
     * @return 反序列化结果，空输入返回空集合
     */
    public static <T> List<T> parseList(String json, Class<T> elementClazz) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    TypeFactory.defaultInstance().constructCollectionType(List.class, elementClazz));
        } catch (JsonProcessingException e) {
            throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR, "JSON 数组反序列化失败", e);
        }
    }

    /**
     * JSON 对象字符串反序列化为 Map。
     *
     * @param json JSON 字符串
     * @return 反序列化结果，空输入返回空 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR, "JSON 转 Map 失败", e);
        }
    }
}
