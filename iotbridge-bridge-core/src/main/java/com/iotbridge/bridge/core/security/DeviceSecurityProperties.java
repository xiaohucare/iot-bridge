package com.iotbridge.bridge.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备接入认证配置，前缀 {@code iotbridge.security}。
 *
 * <p>解决的是设计评审 P0-1：TCP 接入的设备号来自帧内 IMEI，任何知道设备号的一方都能顶替会话；
 * MQTT 侧原先只有一个全局账号密码，知道它即可冒用任意 clientId。本配置引入「设备注册表」，
 * 按 {@code (protocol, deviceId)} 登记授权设备及其凭据约束。</p>
 *
 * <p>两个关键开关：</p>
 * <ul>
 *   <li>{@code enabled}：是否启用注册表（默认 {@code true}，但设备列表为空时等价于不限制）；</li>
 *   <li>{@code enforce}：{@code true} 时未通过校验的连接被拒绝（TCP 断链 / MQTT CONNACK 拒绝）；
 *       {@code false} 时只记录告警与指标，用于灰度盘点存量设备。<b>prod 环境要求为 true</b>（见
 *       {@code ProductionSecurityGuard}）。</li>
 * </ul>
 *
 * <p>凭据约束按「越配越严」解释：配了 {@code password} 必须密码匹配；配了 {@code certFingerprint}
 * 必须客户端证书指纹匹配；配了 {@code allowedIps} 必须来源地址命中。三者都未配时只校验设备是否在册。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
@ConfigurationProperties(prefix = "iotbridge.security")
public class DeviceSecurityProperties {

    /**
     * 是否启用设备注册表校验。关闭时认证器直接放行（仅建议本地开发使用）。
     */
    private boolean enabled = true;

    /**
     * 是否强制执行：未通过校验的设备被拒绝接入。默认 {@code false}（观察模式）。
     */
    private boolean enforce = false;

    /**
     * 已授权设备清单。
     */
    private List<DeviceRule> devices = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public List<DeviceRule> getDevices() {
        return devices;
    }

    public void setDevices(List<DeviceRule> devices) {
        this.devices = devices == null ? new ArrayList<>() : devices;
    }

    /**
     * 单条设备授权记录。
     */
    public static class DeviceRule {

        /**
         * 协议标识（{@code sos-c02} / {@code demo-text} / {@code mqtt} 等），需与下行寻址口径一致。
         */
        private String protocol;

        /**
         * 设备号（TCP 为 IMEI，MQTT 为 clientId）。
         */
        private String deviceId;

        /**
         * 是否启用该设备；置 {@code false} 即吊销（保留记录便于审计与恢复）。
         */
        private boolean enabled = true;

        /**
         * 接入密码（MQTT CONNECT 密码；TCP 无协议内凭据，配了该字段则 TCP 侧必然不通过）。
         */
        private String password;

        /**
         * 允许的客户端证书 SHA-256 指纹（十六进制，冒号可有可无），用于 mTLS 场景。
         */
        private String certFingerprint;

        /**
         * 允许的来源 IP 列表；为空表示不限制来源。
         */
        private List<String> allowedIps = new ArrayList<>();

        /**
         * 备注（设备型号、安装位置等），仅用于运维阅读。
         */
        private String note;

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getCertFingerprint() {
            return certFingerprint;
        }

        public void setCertFingerprint(String certFingerprint) {
            this.certFingerprint = certFingerprint;
        }

        public List<String> getAllowedIps() {
            return allowedIps;
        }

        public void setAllowedIps(List<String> allowedIps) {
            this.allowedIps = allowedIps == null ? new ArrayList<>() : allowedIps;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
