package com.xx.jttprotocolparse.core;

import com.xx.jttprotocolparse.core.descr.DescriptorRegistry;
import com.xx.jttprotocolparse.core.engine.ParseEngine;
import com.xx.jttprotocolparse.core.frame.GB32960FrameAdapter;
import com.xx.jttprotocolparse.core.result.Issue;
import com.xx.jttprotocolparse.core.result.Node;
import com.xx.jttprotocolparse.core.result.ParseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归样例与 web/index.html 页面共用同一组 hex，防止改 YAML/引擎时悄悄破坏。
 * 构造：起始符 ## + 命令 + 应答 + VIN(VIN1234567890ABCD) + 未加密 + 长度 + 数据体 + BCC。
 */
class ParseEngineTest {

    private static final String VIN = "56494E3132333435363738393041424344";
    private final ParseEngine engine = new ParseEngine(new DescriptorRegistry(), List.of(new GB32960FrameAdapter()));

    // 与 web/index.html 样例保持一致
    private static final String LOGIN = "232301FE" + VIN + "0100081A0A14112300000195";

    // 实时上报：采集时间(十进制字节) + 整车数据(0x01, 20B) + 驱动电机数据(0x02, 1B个数 + 1个电机12B)
    private static final String REALTIME_BODY = "1A0A14112300"
            + "01" + "01030103E8000100000FA003E832010113880000"
            + "02" + "010101327530324E200FA003E8";
    private static final String REALTIME = frame(0x02, 0xFE, REALTIME_BODY);

    // 覆盖 0x01~0x09 与 0x80 的全量子类型样例（按协议字段逐段构造）
    private static final String ALL_SUBTYPES_BODY = "1A0A14112300"
            + "01" + "01030103E8000100000FA003E832010113880000"
            + "02" + "010101327530324E200FA003E8"
            + "03" + "0FA003E80000000232320BB80100640200640301"
            + "04" + "0100000000"
            + "05" + "00075BCD15075BCD15"
            + "06" + "01010BB801010BB8010132010132"
            + "07" + "01000000010100000001000000"
            + "08" + "01010FA003E800040001020BB80BB9"
            + "09" + "010100023232"
            + "80" + "0016000100020003000000000000011234567802AABB";

    @Test
    void vehicleLogin() {
        ParseResult r = one(LOGIN);
        assertTrue(r.isOk(), issues(r));
        assertEquals("GB/T 32960.3 新能源汽车国标 · breton · 车辆登录", r.getReport());
        assertEquals("2026-10-20 17:35:00", value(children(r, "车辆登录"), "登录时间"));
        assertEquals("1", value(children(r, "车辆登录"), "登录流水号"));
        // 单流输出尾部 = 校验码
        assertEquals("校验码(BCC)", r.getNodes().get(r.getNodes().size() - 1).getLabel());
    }

    @Test
    void realtimeVehicleData() {
        ParseResult r = one(REALTIME);
        assertTrue(r.isOk(), issues(r));
        assertEquals("2026-10-20 17:35:00", value(r.getNodes(), "数据采集时间"));

        Node vehicle = node(r.getNodes(), "整车数据");
        assertNotNull(vehicle, "整车数据节点缺失");
        List<Node> fields = vehicle.getChildren();
        assertEquals("启动态", value(fields, "车辆状态"));
        assertEquals("未充电状态", value(fields, "充电状态"));
        assertEquals("纯电", value(fields, "运行模式"));
        assertEquals("100", value(fields, "车速"));         // 1000 * 0.1 km/h
        assertEquals("6553.6", value(fields, "累计里程"));    // 65536 * 0.1 km
        assertEquals("400", value(fields, "总电压"));       // 4000 * 0.1 V
        assertEquals("0", value(fields, "总电流"));          // (1000-1000) * 0.1 A
        assertEquals("50", value(fields, "SOC"));
        assertEquals("工作", value(fields, "DC-DC状态"));
        assertEquals("1档", value(fields, "档位"));
        Node gear = node(fields, "档位");
        assertNotNull(gear, "档位节点缺失");
        assertEquals("无制动力", value(gear.getChildren(), "制动力"));
        assertEquals("无驱动力", value(gear.getChildren(), "驱动力"));
        assertEquals("5000", value(fields, "绝缘电阻"));
        assertEquals("0", value(fields, "加速踏板行程值"));
        assertEquals("0", value(fields, "制动踏板状态"));

        Node motorPacket = node(r.getNodes(), "驱动电机数据");
        assertNotNull(motorPacket, "驱动电机数据节点缺失");
        assertEquals("1", value(motorPacket.getChildren(), "驱动电机个数"));
        Node motor = node(motorPacket.getChildren(), "驱动电机总成信息列表 1");
        assertNotNull(motor, "驱动电机总成信息列表节点缺失");
        List<Node> motorFields = motor.getChildren();
        assertEquals("1", value(motorFields, "驱动电机序号"));
        assertEquals("耗电", value(motorFields, "驱动电机状态"));
        assertEquals("10", value(motorFields, "电机控制器温度"));   // 50 - 40
        assertEquals("0", value(motorFields, "驱动电机转速"));      // 30000 - 30000
        assertEquals("10", value(motorFields, "驱动电机温度"));     // 50 - 40
        assertEquals("0", value(motorFields, "驱动电机转矩"));      // (20000-20000)*0.1
        assertEquals("400", value(motorFields, "电机控制器输入电压")); // 4000*0.1
        assertEquals("0", value(motorFields, "电机控制器直流母线电流"));
    }

    @Test
    void gearBits() {
        // 档位 0x31 = b3~b0 0001(1档) + b4 有制动力 + b5 有驱动力
        String body = "1A0A14112300"
                + "01" + "01030103E8000100000FA003E832013113880000";
        ParseResult r = one(frame(0x02, 0xFE, body));
        assertTrue(r.isOk(), issues(r));

        Node vehicle = node(r.getNodes(), "整车数据");
        assertNotNull(vehicle, "整车数据节点缺失");
        Node gear = node(vehicle.getChildren(), "档位");
        assertNotNull(gear, "档位节点缺失");
        assertEquals("1档", gear.getValue());
        assertEquals("有制动力", value(gear.getChildren(), "制动力"));
        assertEquals("有驱动力", value(gear.getChildren(), "驱动力"));
    }

    @Test
    void realtimeAllSubtypes() {
        ParseResult r = one(frame(0x02, 0xFE, ALL_SUBTYPES_BODY));
        assertTrue(r.isOk(), issues(r));
        // 6 报文头 + 采集时间 + 10 个子报文 + 校验码
        assertEquals(18, r.getNodes().size());
        // 单流顺序：报文头在前、主体居中、校验码收尾
        assertEquals("起始符", r.getNodes().get(0).getLabel());
        assertEquals("整车数据", r.getNodes().get(7).getLabel());
        assertEquals("驱动电机数据", r.getNodes().get(8).getLabel());
        assertEquals("燃料电池数据", r.getNodes().get(9).getLabel());
        assertEquals("发动机数据", r.getNodes().get(10).getLabel());
        assertEquals("车辆位置数据", r.getNodes().get(11).getLabel());
        assertEquals("极值数据", r.getNodes().get(12).getLabel());
        assertEquals("报警数据", r.getNodes().get(13).getLabel());
        assertEquals("可充电储能装置电压数据", r.getNodes().get(14).getLabel());
        assertEquals("可充电储能装置温度数据", r.getNodes().get(15).getLabel());
        assertEquals("扩展部分数据", r.getNodes().get(16).getLabel());
        assertEquals("校验码(BCC)", r.getNodes().get(17).getLabel());

        Node fuelCell = r.getNodes().get(9);
        assertEquals("2", value(fuelCell.getChildren(), "燃料电池温度探针总数"));
        assertEquals("-10", value(fuelCell.getChildren(), "氢系统中最高温度")); // 3000*0.01 - 40
        Node probe = node(fuelCell.getChildren(), "探针温度值 1");
        assertNotNull(probe);
        assertEquals("10", value(probe.getChildren(), "探针温度"));

        Node position = r.getNodes().get(11);
        assertEquals("有效定位；北纬；东经", value(position.getChildren(), "定位状态"));
        assertEquals("123.456789", value(position.getChildren(), "经度"));

        Node alarm = r.getNodes().get(13);
        assertEquals("一级故障", value(alarm.getChildren(), "最高报警等级"));
        // 通用报警标志：19 段各带 label，输出为子节点行（label: 值）
        Node flags = node(alarm.getChildren(), "通用报警标志");
        assertNotNull(flags, "通用报警标志节点缺失");
        assertEquals(19, flags.getChildren().size());
        Node flag0 = flags.getChildren().get(0);
        assertEquals("温度差异", flag0.getLabel());
        assertEquals("报警", flag0.getValue());   // 样例置位 bit0
        assertEquals("正常", flags.getChildren().get(1).getValue());
        assertEquals("正常", flags.getChildren().get(18).getValue());
        Node fault = node(alarm.getChildren(), "可充电储能装置故障代码列表 1");
        assertNotNull(fault);
        assertEquals("00000001", value(fault.getChildren(), "故障代码"));

        Node battery = r.getNodes().get(14);
        Node subsystem = node(battery.getChildren(), "可充电储能子系统电压信息列表 1");
        assertNotNull(subsystem);
        Node voltages = node(subsystem.getChildren(), "单体电池电压列表 1");
        assertNotNull(voltages);
        assertEquals("3", value(voltages.getChildren(), "单体电池电压"));  // 0x0BB8 * 0.001

        Node temp = r.getNodes().get(15);
        Node tempItem = node(temp.getChildren(), "可充电储能子系统温度信息列表 1");
        assertNotNull(tempItem);
        Node tempList = node(tempItem.getChildren(), "可充电储能子系统温度值列表 1");
        assertNotNull(tempList);
        assertEquals("10", value(tempList.getChildren(), "温度值"));

        Node ext = r.getNodes().get(16);
        assertEquals("22", value(ext.getChildren(), "自定义数据长度"));
        // 终端状态：两段各带 label，输出为子节点行
        Node terminal = node(ext.getChildren(), "终端状态");
        assertNotNull(terminal, "终端状态节点缺失");
        assertEquals(2, terminal.getChildren().size());
        assertEquals("CAN数据状态", terminal.getChildren().get(0).getLabel());
        assertEquals("有CAN数据", terminal.getChildren().get(0).getValue());   // 样例 0x00
        assertEquals("TF卡状态", terminal.getChildren().get(1).getLabel());
        assertEquals("无TF卡", terminal.getChildren().get(1).getValue());
        assertEquals("1", value(ext.getChildren(), "Can帧个数"));
        Node can = node(ext.getChildren(), "CAN帧列表 1");
        assertNotNull(can);
        assertEquals("12345678", value(can.getChildren(), "canID"));
        assertEquals("AABB", value(can.getChildren(), "Can帧数据"));
    }

    @Test
    void vehicleLogout() {
        // 0x04 车辆登出：登出时间(dec-time) + 登出流水号(u2)
        ParseResult r = one(frame(0x04, 0xFE, "1A0A14112300" + "0001"));
        assertTrue(r.isOk(), issues(r));
        assertEquals("GB/T 32960.3 新能源汽车国标 · breton · 车辆登出", r.getReport());
        assertEquals("2026-10-20 17:35:00", value(children(r, "车辆登出"), "登出时间"));
        assertEquals("1", value(children(r, "车辆登出"), "登出流水号"));
        assertEquals("校验码(BCC)", r.getNodes().get(r.getNodes().size() - 1).getLabel());
    }

    @Test
    void badChecksum_尽力解析() {
        String bad = LOGIN.substring(0, LOGIN.length() - 2) + "90";
        ParseResult r = one(bad);
        assertTrue(r.getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("CHECKSUM_MISMATCH")), issues(r));
        // 明文字段仍应解析出来（尽力解析），校验码节点尾部标注校验不符
        assertEquals("2026-10-20 17:35:00", value(children(r, "车辆登录"), "登录时间"));
        Node bcc = r.getNodes().get(r.getNodes().size() - 1);
        assertEquals("校验码(BCC)", bcc.getLabel());
        assertEquals("校验不符", bcc.getCheck());
    }

    @Test
    void unknownCommand() {
        ParseResult r = one(frame(0x7F, 0xFE, "000102"));
        assertTrue(r.getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("UNKNOWN_COMMAND")), issues(r));
        // 统一收口：数据单元打包「未知报文」，展示报文原文
        Node unk = node(r.getNodes(), "未知报文");
        assertNotNull(unk, "未知报文节点缺失");
        assertEquals("000102", unk.getValue());
        assertEquals("未知命令字 0x7F", unk.getNote());
        assertEquals("校验码(BCC)", r.getNodes().get(r.getNodes().size() - 1).getLabel());
    }

    @Test
    void protocolNotRegistered_头部正常剩余打包未知() {
        // 帧层认识 gc-32960，但厂商未登记：报文头正常解析，数据单元打包「未知报文」
        ParseResult r = engine.parse("gc-32960", "2016", "no-vendor", LOGIN).get(0);
        assertFalse(r.isOk(), issues(r));
        assertTrue(r.getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("UNSUPPORTED_PROTOCOL")), issues(r));
        assertEquals("##", value(r.getNodes(), "起始符"));
        Node unk = node(r.getNodes(), "未知报文");
        assertNotNull(unk, "未知报文节点缺失");
        assertEquals("1A0A141123000001", unk.getValue());
        // 校验码仍在数据单元收尾位置
        assertEquals("校验码(BCC)", r.getNodes().get(r.getNodes().size() - 1).getLabel());
    }

    @Test
    void adapterMissing_整条打包未知() {
        ParseEngine noAdapter = new ParseEngine(new DescriptorRegistry(), List.of());
        ParseResult r = noAdapter.parse("gc-32960", "2016", "breton", LOGIN).get(0);
        assertTrue(r.getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("UNSUPPORTED_PROTOCOL")), issues(r));
        Node unk = node(r.getNodes(), "未知报文");
        assertNotNull(unk, "未知报文节点缺失");
        assertEquals(LOGIN, unk.getValue());
    }

    @Test
    void frameNotMatch_整条打包未知() {
        ParseResult r = one("1234AABBCC");
        assertTrue(r.getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("FRAME_NOT_MATCH")), issues(r));
        Node unk = node(r.getNodes(), "未知报文");
        assertNotNull(unk, "未知报文节点缺失");
        assertEquals("1234AABBCC", unk.getValue());
    }

    @Test
    void multiPaste() {
        // 多条粘贴：登录报文 / 实时上报 / 非报文杂串
        List<ParseResult> rs = engine.parse("gc-32960", "2016", "breton", LOGIN + "\n\n" + REALTIME + "\n1 2");
        assertEquals(3, rs.size());
        assertTrue(rs.get(0).isOk());
        assertTrue(rs.get(1).isOk());
        assertTrue(rs.get(2).getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("FRAME_NOT_MATCH")), issues(rs.get(2)));
    }

    @Test
    void resendUsesSameSubtypeTable() {
        ParseResult r = one(frame(0x03, 0xFE, REALTIME_BODY));
        assertTrue(r.isOk(), issues(r));
        assertEquals("GB/T 32960.3 新能源汽车国标 · breton · 补发信息上报", r.getReport());
        assertEquals("2026-10-20 17:35:00", value(r.getNodes(), "数据采集时间"));
        Node packet = node(r.getNodes(), "整车数据");
        assertNotNull(packet, "整车数据节点缺失");
        assertEquals("100", value(packet.getChildren(), "车速"));
    }

    @Test
    void unknownSubtype_recordsIssueAndPreservesRest() {
        // 0x0A 未在 subtype 表中定义，无法确定长度：记录 issue，剩余字节原样保留
        ParseResult r = one(frame(0x02, 0xFE, "1A0A14112300" + "0A" + "AABBCC"));
        assertTrue(r.getIssues().stream().anyMatch(i -> IssueCodeOf(i).equals("UNKNOWN_SUBTYPE")), issues(r));
        // 统一收口：未知子报文连同其后续全部打包为「未知报文」
        Node packet = node(r.getNodes(), "未知报文");
        assertNotNull(packet, "未知报文节点缺失");
        assertEquals("AABBCC", packet.getValue());
        assertTrue(packet.getNote().contains("0x0A"), "note 应带子报文类型");
    }

    // ---- 样例帧构造（测试专用）：保证 BCC 正确 ----

    private static String frame(int cmd, int ack, String bodyHex) {
        byte[] b = hex(bodyHex);
        byte[] vin = hex(VIN);
        byte[] out = new byte[2 + 1 + 1 + 17 + 1 + 2 + b.length + 1];
        out[0] = 0x23;
        out[1] = 0x23;
        out[2] = (byte) cmd;
        out[3] = (byte) ack;
        System.arraycopy(vin, 0, out, 4, 17);
        out[21] = 0x01;
        out[22] = (byte) (b.length >> 8);
        out[23] = (byte) b.length;
        System.arraycopy(b, 0, out, 24, b.length);
        int x = 0;
        for (int i = 2; i < out.length - 1; i++) {
            x ^= out[i] & 0xFF;
        }
        out[out.length - 1] = (byte) x;
        return hex(out);
    }

    private static String IssueCodeOf(Issue i) {
        return i.getCode();
    }

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }

    private ParseResult one(String h) {
        return engine.parse("gc-32960", "2016", "breton", h).get(0);
    }

    /** 定位报文类型小节节点并取其字段 */
    private static List<Node> children(ParseResult r, String section) {
        Node n = node(r.getNodes(), section);
        assertNotNull(n, section + " 节点缺失");
        return n.getChildren();
    }

    private static String value(List<Node> list, String label) {
        return list.stream().filter(n -> label.equals(n.getLabel())).findFirst().map(n -> n.getValue()).orElse(null);
    }

    private static Node node(List<Node> list, String label) {
        return list.stream().filter(n -> label.equals(n.getLabel())).findFirst().orElse(null);
    }

    private static String issues(ParseResult r) {
        return r.getIssues().stream().map(i -> i.getCode() + ": " + i.getMessage()).reduce((a, b) -> a + " / " + b).orElse("<无>");
    }
}
