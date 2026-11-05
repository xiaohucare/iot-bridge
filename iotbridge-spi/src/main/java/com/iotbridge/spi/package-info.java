/**
 * iotbridge-spi：面向协议插件的稳定契约层。
 *
 * <p>包含编解码接口 {@code ProtocolCodec}、上/下行消息模型、帧规则以及跨模块解耦的回调接口。
 * 该模块尽量不引入 Spring，保证插件可独立编译与打包。</p>
 */
package com.iotbridge.spi;
