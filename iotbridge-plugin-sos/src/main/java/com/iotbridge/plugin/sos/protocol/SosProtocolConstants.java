package com.iotbridge.plugin.sos.protocol;

import java.util.Map;

/**
 * SOS 通信协议 V3.0 常量定义（帧头/帧尾、协议版本、命令集、设备类型、事件类型、回复标识）。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class SosProtocolConstants {

    private SosProtocolConstants() {
        // 常量类不允许实例化
    }

    /**
     * 协议类型标识（与 ProtocolCodec.protocolType() 一致）。
     */
    public static final String PROTOCOL_TYPE = "sos-c02";

    /**
     * 帧头：固定 3 字节 0x57 0x54 0x4B（ASCII "WTK"）。
     */
    public static final byte[] FRAME_HEADER = {(byte) 0x57, (byte) 0x54, (byte) 0x4B};

    /**
     * 帧尾：固定 3 字节 0x45 0x4E 0x44（ASCII "END"）。
     */
    public static final byte[] FRAME_TAIL = {(byte) 0x45, (byte) 0x4E, (byte) 0x44};

    /**
     * 当前协议版本 V3.0 = 0x30。
     */
    public static final byte PROTOCOL_VERSION = (byte) 0x30;

    /**
     * 帧头长度（字节）。
     */
    public static final int HEADER_LEN = 3;
    /**
     * 帧尾长度（字节）。
     */
    public static final int TAIL_LEN = 3;

    // ==== 命令集 ====
    public static final byte CMD_REGISTER = (byte) 0x01;
    public static final byte CMD_EVENT = (byte) 0x02;
    public static final byte CMD_DOWN = (byte) 0x03;
    public static final byte CMD_DOWN_REPLY = (byte) 0x04;
    public static final byte CMD_KEEPALIVE = (byte) 0x05;
    public static final byte CMD_DATA_REPORT = (byte) 0x06;
    public static final byte CMD_TIME_CALIB = (byte) 0x0B;
    public static final byte CMD_SERVER_REPLY = (byte) (byte) 0xFF;

    /**
     * 命令码 -> 名称。
     */
    public static final Map<Integer, String> COMMAND_NAMES = Map.ofEntries(
            Map.entry(0x01, "设备注册"),
            Map.entry(0x02, "设备事件上报"),
            Map.entry(0x03, "指令下发"),
            Map.entry(0x04, "下发指令回复"),
            Map.entry(0x05, "长连接数据包"),
            Map.entry(0x06, "数据上报"),
            Map.entry(0x07, "OTA升级请求"),
            Map.entry(0x08, "OTA服务器信息下发"),
            Map.entry(0x09, "OTA升级状态上报"),
            Map.entry(0x0A, "OTA升级取消指令"),
            Map.entry(0x0B, "时间校准"),
            Map.entry(0xFF, "服务器回复"));

    // ==== 设备类型 ====
    /**
     * 设备类型码 -> 名称。
     */
    public static final Map<Integer, String> DEVICE_TYPE_NAMES = Map.ofEntries(
            Map.entry(0x01, "门磁"),
            Map.entry(0x02, "燃气"),
            Map.entry(0x03, "烟感"),
            Map.entry(0x04, "手报(紧急按钮)"),
            Map.entry(0x05, "一氧化碳"),
            Map.entry(0x06, "温感"),
            Map.entry(0x07, "声光"),
            Map.entry(0x08, "水浸"),
            Map.entry(0x09, "报警器"),
            Map.entry(0x0A, "断电报警器"),
            Map.entry(0x0B, "红外报警器"),
            Map.entry(0x0C, "定位器"),
            Map.entry(0x0D, "人体存在"),
            Map.entry(0x0E, "井盖"),
            Map.entry(0x0F, "水压"),
            Map.entry(0x10, "液位"),
            Map.entry(0x11, "消火栓"),
            Map.entry(0x12, "多按键紧急按钮"),
            Map.entry(0x13, "智能胸牌"));

    // ==== 事件类型 ====
    /**
     * 事件类型码 -> 名称。
     */
    public static final Map<Integer, String> EVENT_TYPE_NAMES = Map.ofEntries(
            Map.entry(0x01, "心跳"),
            Map.entry(0x02, "报警"),
            Map.entry(0x03, "报警恢复"),
            Map.entry(0x04, "防拆报警"),
            Map.entry(0x05, "防拆报警恢复"),
            Map.entry(0x06, "低电压"),
            Map.entry(0x07, "低电压恢复"),
            Map.entry(0x08, "传感器故障"),
            Map.entry(0x09, "传感器故障恢复"),
            Map.entry(0x0A, "测试报警"),
            Map.entry(0x0B, "测试报警恢复"),
            Map.entry(0x0C, "紧急报警"),
            Map.entry(0x0D, "紧急报警恢复"),
            Map.entry(0x0E, "温度报警"),
            Map.entry(0x0F, "温度报警恢复"),
            Map.entry(0x10, "无人报警"),
            Map.entry(0x11, "无人报警恢复"),
            Map.entry(0x12, "有人报警"),
            Map.entry(0x13, "有人报警恢复"),
            Map.entry(0x14, "驻留报警"),
            Map.entry(0x15, "驻留报警恢复"),
            Map.entry(0x16, "跌倒报警"),
            Map.entry(0x17, "跌倒报警恢复"),
            Map.entry(0x18, "静止驻留报警"),
            Map.entry(0x19, "静止驻留报警恢复"),
            Map.entry(0x20, "按键触发"));

    // ==== 服务器回复标识 ====
    public static final byte REPLY_OK = (byte) 0x00;
    public static final byte REPLY_CRC_FAIL = (byte) 0x01;
    public static final byte REPLY_PARSE_FAIL = (byte) 0x02;
    public static final byte REPLY_VENDOR_FAIL = (byte) 0x03;

    /**
     * 设备编号 IMEI 固定字节长度（ASCII）。
     */
    public static final int IMEI_LEN = 15;
}
