package com.iotbridge.plugin.sos.model;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.plugin.sos.protocol.Crc16Xmodem;
import com.iotbridge.plugin.sos.protocol.HexUtil;
import com.iotbridge.plugin.sos.protocol.SosProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * SOS 协议 V3.0 数据帧。
 *
 * <p>帧结构（大端）：</p>
 * <pre>
 * 帧头(3) 版本(1) 厂商标识(2) 数据序列号(2) 命令(1) 数据长度(2) 数据体(N) CRC16(2) 帧尾(3)
 * 57 54 4B  0x30    vendor      seq        cmd     N          data      crc     45 4E 44
 * </pre>
 *
 * <p>校验范围 = 帧头 + 版本 + 厂商标识 + 序列号 + 命令 + 数据长度 + 数据体。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class SosFrame {

    /**
     * 固定开销字节数：帧头3+版本1+厂商2+序列2+命令1+长度2+CRC2+帧尾3 = 16。
     */
    public static final int OVERHEAD = 16;

    private byte version = SosProtocolConstants.PROTOCOL_VERSION;
    private byte[] vendor = {0x00, 0x00};
    private int sequence;
    private byte command;
    private byte[] data = new byte[0];

    public SosFrame() {
    }

    public SosFrame(byte version, byte[] vendor, int sequence, byte command, byte[] data) {
        this.version = version;
        if (vendor != null && vendor.length == 2) {
            this.vendor = vendor;
        }
        this.sequence = sequence & 0xFFFF;
        this.command = command;
        if (data != null) {
            this.data = data;
        }
    }

    /**
     * 从完整帧字节解析。
     *
     * @param frame 完整帧（含帧头帧尾）
     * @return 解析后的帧对象
     */
    public static SosFrame parse(byte[] frame) {
        if (frame == null || frame.length < OVERHEAD) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR,
                    "帧长度不足，实际=" + (frame == null ? 0 : frame.length));
        }
        if (!startsWith(frame, SosProtocolConstants.FRAME_HEADER)) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR,
                    "帧头非法: " + HexUtil.toHex(Arrays.copyOf(frame, SosProtocolConstants.HEADER_LEN)));
        }
        if (!endsWith(frame, SosProtocolConstants.FRAME_TAIL)) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR, "帧尾非法");
        }
        int dataLen = ((frame[9] & 0xFF) << 8) | (frame[10] & 0xFF);
        int expectedLen = OVERHEAD + dataLen;
        if (frame.length != expectedLen) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR,
                    "数据长度与帧长不一致，声明长度=" + dataLen + " 期望帧长=" + expectedLen + " 实际=" + frame.length);
        }

        // 校验 CRC：范围 = 帧头..数据体（不含 CRC 与帧尾）
        int crcCalc = Crc16Xmodem.compute(frame, 0, 11 + dataLen);
        int crcFrame = ((frame[11 + dataLen] & 0xFF) << 8) | (frame[12 + dataLen] & 0xFF);
        if (crcCalc != crcFrame) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR,
                    "CRC 校验失败，计算=" + Integer.toHexString(crcCalc) + " 帧内=" + Integer.toHexString(crcFrame));
        }

        byte version = frame[3];
        byte[] vendor = Arrays.copyOfRange(frame, 4, 6);
        int seq = ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        byte command = frame[8];
        byte[] data = dataLen == 0 ? new byte[0] : Arrays.copyOfRange(frame, 11, 11 + dataLen);
        return new SosFrame(version, vendor, seq, command, data);
    }

    /**
     * 序列化为完整帧字节（自动填充数据长度、CRC 与帧尾）。
     *
     * @return 帧字节
     */
    public byte[] toBytes() {
        int dataLen = data == null ? 0 : data.length;
        byte[] out = new byte[OVERHEAD + dataLen];
        int i = 0;
        System.arraycopy(SosProtocolConstants.FRAME_HEADER, 0, out, i, 3);
        i += 3;
        out[i++] = version;
        out[i++] = vendor[0];
        out[i++] = vendor[1];
        out[i++] = (byte) ((sequence >> 8) & 0xFF);
        out[i++] = (byte) (sequence & 0xFF);
        out[i++] = command;
        out[i++] = (byte) ((dataLen >> 8) & 0xFF);
        out[i++] = (byte) (dataLen & 0xFF);
        if (dataLen > 0) {
            System.arraycopy(data, 0, out, i, dataLen);
            i += dataLen;
        }
        int crc = Crc16Xmodem.compute(out, 0, 11 + dataLen);
        out[i++] = (byte) ((crc >> 8) & 0xFF);
        out[i++] = (byte) (crc & 0xFF);
        System.arraycopy(SosProtocolConstants.FRAME_TAIL, 0, out, i, 3);
        return out;
    }

    /**
     * 读取数据体中指定区间的 ASCII 字符串（如 IMEI/ICCID）。
     *
     * @param offset 数据体内起始下标
     * @param length 长度
     * @return ASCII 字符串
     */
    public String readAscii(int offset, int length) {
        if (data == null || offset < 0 || offset + length > data.length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.US_ASCII).trim();
    }

    /**
     * 读取数据体内单字节无符号整数值。
     *
     * @param offset 数据体内下标
     * @return 0~255 的值；越界返回 -1
     */
    public int readUnsignedByte(int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            return -1;
        }
        return data[offset] & 0xFF;
    }

    /**
     * 读取数据体剩余字节（如 JSON 位置信息）。
     *
     * @param offset 数据体内起始下标
     * @return 剩余字节，越界返回空数组
     */
    public byte[] readRemainBytes(int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, offset, data.length);
    }

    public byte getVersion() {
        return version;
    }

    public void setVersion(byte version) {
        this.version = version;
    }

    public byte[] getVendor() {
        return vendor;
    }

    public void setVendor(byte[] vendor) {
        if (vendor != null && vendor.length == 2) {
            this.vendor = vendor;
        }
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence & 0xFFFF;
    }

    public byte getCommand() {
        return command;
    }

    public void setCommand(byte command) {
        this.command = command;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    /**
     * 整帧的十六进制字符串，便于日志与测试比对。
     *
     * @return 大写十六进制字符串
     */
    public String toHex() {
        return HexUtil.toHex(toBytes());
    }

    private static boolean startsWith(byte[] src, byte[] prefix) {
        if (src.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (src[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(byte[] src, byte[] suffix) {
        if (src.length < suffix.length) {
            return false;
        }
        for (int i = 0; i < suffix.length; i++) {
            if (src[src.length - suffix.length + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }
}
