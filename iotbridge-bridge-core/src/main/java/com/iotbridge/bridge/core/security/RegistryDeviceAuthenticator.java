package com.iotbridge.bridge.core.security;

import com.iotbridge.bridge.core.metrics.BridgeMetrics;
import com.iotbridge.spi.security.DeviceAuthResult;
import com.iotbridge.spi.security.DeviceAuthenticator;
import com.iotbridge.spi.security.DeviceCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于配置注册表的设备认证器。
 *
 * <p>键为 {@code (protocol, deviceId)}，与下行寻址、离线缓冲同一口径，避免“认证用一套标识、
 * 投递用另一套标识”导致绕过的缝隙。凭据校验顺序：设备在册 → 未停用 → 来源 IP → 证书指纹 → 密码。</p>
 *
 * <p>安全细节：</p>
 * <ul>
 *   <li>密码比较用 {@link MessageDigest#isEqual(byte[], byte[])}，耗时与内容无关，避免逐字节短路比较被计时探测；</li>
 *   <li>设备号/协议匹配前统一 {@code trim}，但不做大小写归一（IMEI 与 clientId 区分大小写更安全）；</li>
 *   <li>证书指纹比较前去掉冒号并转小写，兼容 {@code openssl} 输出格式；</li>
 *   <li>注册表为空时返回 {@link DeviceAuthResult#UNKNOWN_DEVICE}，而不是放行——空清单即“谁都不信”。</li>
 * </ul>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
@Component
public class RegistryDeviceAuthenticator implements DeviceAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(RegistryDeviceAuthenticator.class);

    private final DeviceSecurityProperties properties;
    private final BridgeMetrics metrics;
    private final Map<String, DeviceSecurityProperties.DeviceRule> rules = new HashMap<>();

    /**
     * 测试与无指标场景构造。
     *
     * @param properties 设备安全配置
     */
    public RegistryDeviceAuthenticator(DeviceSecurityProperties properties) {
        this(properties, BridgeMetrics.NOOP);
    }

    /**
     * Spring 注入构造。
     *
     * @param properties 设备安全配置
     * @param metrics    指标（认证结果按协议与结果计数）
     */
    @Autowired
    public RegistryDeviceAuthenticator(DeviceSecurityProperties properties, BridgeMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        index(properties);
    }

    /**
     * 建立索引；重复键以最后一条为准并告警（配置合并常见，不视为错误）。
     *
     * @param properties 设备安全配置
     */
    private void index(DeviceSecurityProperties properties) {
        for (DeviceSecurityProperties.DeviceRule rule : properties.getDevices()) {
            if (rule == null || isBlank(rule.getProtocol()) || isBlank(rule.getDeviceId())) {
                log.warn("忽略非法设备授权记录（protocol/deviceId 不能为空）：{}", rule);
                continue;
            }
            String key = key(rule.getProtocol().trim(), rule.getDeviceId().trim());
            if (rules.put(key, rule) != null) {
                log.warn("重复设备授权记录，后者覆盖前者 protocol={} deviceId={}", rule.getProtocol(), rule.getDeviceId());
            }
        }
        log.info("设备注册表已加载 size={} enforce={} enabled={}", rules.size(), properties.isEnforce(),
                properties.isEnabled());
    }

    @Override
    public DeviceAuthResult authenticate(DeviceCredential credential) {
        DeviceAuthResult result = evaluate(credential);
        if (credential != null) {
            metrics.securityAuth(credential.getProtocolType(), result.tag());
        }
        return result;
    }

    /**
     * 做出判定（不计指标），便于在同一处集中维护规则顺序。
     *
     * @param credential 认证输入
     * @return 判定结果
     */
    private DeviceAuthResult evaluate(DeviceCredential credential) {
        if (!properties.isEnabled()) {
            return DeviceAuthResult.NOT_CONFIGURED;
        }
        if (credential == null || isBlank(credential.getProtocolType()) || isBlank(credential.getDeviceId())) {
            return DeviceAuthResult.UNKNOWN_DEVICE;
        }
        DeviceSecurityProperties.DeviceRule rule = rules.get(
                key(credential.getProtocolType().trim(), credential.getDeviceId().trim()));
        if (rule == null) {
            return DeviceAuthResult.UNKNOWN_DEVICE;
        }
        if (!rule.isEnabled()) {
            return DeviceAuthResult.DISABLED;
        }
        if (!rule.getAllowedIps().isEmpty() && !ipAllowed(rule.getAllowedIps(), credential.getRemoteAddress())) {
            return DeviceAuthResult.NETWORK_DENIED;
        }
        if (!isBlank(rule.getCertFingerprint())) {
            String expected = normalizeFingerprint(rule.getCertFingerprint());
            if (!expected.equals(normalizeFingerprint(credential.getCertFingerprint()))) {
                return DeviceAuthResult.BAD_CREDENTIAL;
            }
        }
        if (!isBlank(rule.getPassword())) {
            if (!constantTimeEquals(rule.getPassword(), credential.getPassword())) {
                return DeviceAuthResult.BAD_CREDENTIAL;
            }
        }
        return DeviceAuthResult.ALLOWED;
    }

    @Override
    public boolean isEnforcing() {
        return properties.isEnforce();
    }

    /**
     * 来源地址是否允许。支持精确 IP 与 {@code 10.0.0.*} 形式的前缀通配。
     *
     * @param allowed 允许列表
     * @param remote  远端地址（{@code /10.0.0.1:52341} 或 {@code 10.0.0.1}）
     * @return 命中为 {@code true}
     */
    private boolean ipAllowed(List<String> allowed, String remote) {
        String host = hostOf(remote);
        if (host == null) {
            return false;
        }
        for (String pattern : allowed) {
            if (isBlank(pattern)) {
                continue;
            }
            String trimmed = pattern.trim();
            if (trimmed.endsWith("*") && host.startsWith(trimmed.substring(0, trimmed.length() - 1))) {
                return true;
            }
            if (trimmed.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 Netty 的 {@code InetSocketAddress.toString()} 结果中取主机部分。
     *
     * @param remote 远端地址文本
     * @return 主机部分，无法解析时为 {@code null}
     */
    private String hostOf(String remote) {
        if (isBlank(remote)) {
            return null;
        }
        String value = remote.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : null;
        }
        int colon = value.indexOf(':');
        if (colon > 0 && value.indexOf(':', colon + 1) < 0) {
            value = value.substring(0, colon);
        }
        return value.isEmpty() ? null : value;
    }

    private String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        return fingerprint.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String protocol, String deviceId) {
        return protocol + '\u0000' + deviceId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 供测试与运维检查：当前已加载的授权设备标识。
     *
     * @return {@code protocol/deviceId} 列表
     */
    public List<String> registeredDevices() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, DeviceSecurityProperties.DeviceRule> entry : rules.entrySet()) {
            result.add(entry.getValue().getProtocol() + "/" + entry.getValue().getDeviceId());
        }
        return result;
    }
}
