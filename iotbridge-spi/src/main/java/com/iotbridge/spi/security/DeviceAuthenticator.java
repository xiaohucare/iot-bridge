package com.iotbridge.spi.security;

/**
 * 设备接入认证契约。
 *
 * <p>实现方负责回答「这个 ({@code protocolType}, {@code deviceId}) 是否允许接入、凭据是否匹配」；
 * 调用方负责执行策略：{@link #isEnforcing()} 为 {@code true} 时拒绝未通过认证的连接，
 * 为 {@code false} 时只告警放行（灰度观察期）。把“判定”和“处置”分开，
 * 使同一注册表可以在观察模式下先盘点存量设备，再切换为强制。</p>
 *
 * <p>默认实现 {@link #ALLOW_ALL} 不做任何校验，用于未引入安全模块的测试与单模块场景。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
public interface DeviceAuthenticator {

    /**
     * 不做任何校验的放行实现（非强制模式）。
     */
    DeviceAuthenticator ALLOW_ALL = new DeviceAuthenticator() {
        @Override
        public DeviceAuthResult authenticate(DeviceCredential credential) {
            return DeviceAuthResult.NOT_CONFIGURED;
        }
    };

    /**
     * 校验一次设备接入。
     *
     * @param credential 设备标识与传输层凭据
     * @return 认证结果，不应返回 {@code null}
     */
    DeviceAuthResult authenticate(DeviceCredential credential);

    /**
     * 是否强制执行：为 {@code true} 时调用方必须拒绝未通过认证的连接。
     *
     * @return 强制为 {@code true}
     */
    default boolean isEnforcing() {
        return false;
    }
}
