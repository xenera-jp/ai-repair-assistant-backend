package com.aifieldservice.repairassistant.knowledge;

import java.util.List;

import org.springframework.stereotype.Component;

/** Hoshizaki FH1-SSB 服务手册的报警代码解析 Profile。 */
@Component
public class Fh1SsbServiceManualParser extends ReviewedServiceManualParser {

    @Override
    protected String model() {
        return "FH1-SSB";
    }

    @Override
    protected String parserVersion() {
        return "PDFBOX_FH1_SSB_V2";
    }

    @Override
    protected List<String> identityMarkers() {
        return List.of("FH1-SSB", "SERVICE MANUAL", "Alarm Signals and Countermeasures");
    }

    @Override
    protected List<UnitSpec> unitSpecs() {
        return List.of(
                alarm(
                        "E1",
                        "CABINET_HIGH_TEMP",
                        "High Temperature Alarm",
                        List.of("High Temperature Alarm", "E1", "setpoint range"),
                        "库温持续高于设定范围时触发 E1 高温报警。不同控制板版本使用不同的温差和持续时间阈值。",
                        "庫内温度が設定範囲を継続して上回ると E1 高温警報が発生します。基板リビジョンにより温度差と継続時間の条件が異なります。",
                        "High Temperature Alarm. Cabinet temperature has exceeded the configured setpoint threshold for the specified duration.",
                        List.of("确认门已关闭且门封正常。", "核对设定温度和实测温度。", "检查风路、冷凝器、过滤网和风机。", "温度恢复到设定范围后按 RESET。"),
                        List.of("扉の閉鎖とドアシールを確認します。", "設定温度と実測温度を確認します。", "風路、凝縮器、フィルタ、ファンを点検します。", "温度が設定範囲に戻った後 RESET を押します。"),
                        List.of("SETTING_AIRFLOW_OR_DOOR_SEAL", "REFRIGERANT_CIRCUIT_CAPACITY_LOSS", "SENSOR_FAN_OR_CONTROL_FAILURE")),
                alarm(
                        "E2",
                        "CABINET_TOO_COLD",
                        "Low Temperature Alarm",
                        List.of("Low Temperature Alarm", "E2", "8°F (4.4°C)"),
                        "库温比设定值低 8°F（4.4°C）并持续超过 1 小时时触发 E2 低温报警。",
                        "庫内温度が設定値より 8°F（4.4°C）低い状態で1時間以上継続すると E2 低温警報が発生します。",
                        "Low Temperature Alarm. Cabinet temperature has remained below setpoint by 8°F (4.4°C) for more than 1 hour.",
                        List.of("比较显示温度与独立温度计实测值。", "检查库温传感器。", "确认压缩机继电器是否粘连。", "温度恢复后按 RESET。"),
                        List.of("表示温度と独立温度計の実測値を比較します。", "庫内温度センサを点検します。", "圧縮機リレー接点の溶着を確認します。", "温度復帰後 RESET を押します。"),
                        List.of("CABINET_THERMISTOR_FAILURE", "COMPRESSOR_RELAY_CONTACT_STUCK", "TEMPERATURE_CONTROL_BOARD_FAILURE")),
                alarm(
                        "E3",
                        "DEFROST_FAILURE_FROST",
                        "Defrost Alarm",
                        List.of("Defrost Alarm", "E3", "longer than 1 hour"),
                        "除霜持续超过 1 小时时，控制板终止除霜并触发 E3；连续 4 次超时需要维修人员检查。",
                        "除霜が1時間を超えると制御基板が除霜を終了して E3 を表示し、4回連続で超過した場合はサービス点検が必要です。",
                        "Defrost Alarm. Defrost has taken longer than 1 hour and the control board has terminated defrost.",
                        List.of("确认蒸发器着霜状态。", "测量除霜传感器和安全温控器。", "测量除霜加热器阻值与供电。", "检查除霜频率和控制板输出。"),
                        List.of("蒸発器の着霜状態を確認します。", "除霜センサと安全サーモを測定します。", "除霜ヒータの抵抗と電源を測定します。", "除霜頻度と制御基板出力を確認します。"),
                        List.of("DEFROST_SENSOR_OR_SAFETY_THERMOSTAT", "DEFROST_HEATER_FAILURE", "DEFROST_CONTROL_OR_FREQUENCY_SETTING")),
                alarm(
                        "E4",
                        "HIGH_PRESSURE_CONDENSATION",
                        "High Pressure Alarm",
                        List.of("High Pressure Alarm", "E4", "5 times in 1 hour"),
                        "压缩机排气压力超出正常范围，高压开关在 1 小时内触发 3 次以上时产生 E4；触发 5 次后压缩机停止。",
                        "圧縮機吐出圧力が正常範囲外となり、高圧スイッチが1時間以内に3回以上作動すると E4、5回作動すると圧縮機が停止します。",
                        "High Pressure Alarm. The pressure switch has been triggered 3 or more times in 1 hour; after 5 trips the compressor is stopped.",
                        List.of("清洁过滤网和冷凝器。", "确认冷凝风扇运行和安装通风。", "测量高压侧压力并检查冷媒充注与回路阻塞。", "确认压力正常后检查高压开关。"),
                        List.of("フィルタと凝縮器を清掃します。", "凝縮器ファンと設置換気を確認します。", "高圧側圧力を測定し、冷媒充填量と回路閉塞を確認します。", "圧力正常を確認後、高圧スイッチを点検します。"),
                        List.of("CONDENSER_FILTER_CLOGGING", "REFRIGERANT_CHARGE_OR_CIRCUIT_ABNORMALITY", "HIGH_PRESSURE_SWITCH_ABNORMALITY")),
                alarm(
                        "E6",
                        "COMPRESSOR_START_FAILURE",
                        "High Voltage Alarm",
                        List.of("High Voltage Alarm", "E6", "too high"),
                        "输入电压持续过高至少 10 秒时触发 E6，为保护压缩机，系统停止压缩机。",
                        "入力電圧が10秒以上高すぎる場合 E6 が発生し、圧縮機保護のため圧縮機を停止します。",
                        "High Voltage Alarm. Line voltage has been too high for at least 10 seconds; the compressor has shut down for protection.",
                        List.of("在设备输入端测量供电电压。", "核对电源规格及接线。", "电压恢复到允许范围后确认报警自动复位。"),
                        List.of("機器入力端で供給電圧を測定します。", "電源仕様と配線を確認します。", "電圧が許容範囲に戻った後、自動復帰を確認します。"),
                        List.of("POWER_OR_WIRING_CONNECTION_FAILURE")),
                alarm(
                        "E7",
                        "COMPRESSOR_START_FAILURE",
                        "Low Voltage Alarm",
                        List.of("Low Voltage Alarm", "E7", "too low"),
                        "输入电压持续过低至少 10 秒时触发 E7，为保护压缩机，系统停止压缩机。",
                        "入力電圧が10秒以上低すぎる場合 E7 が発生し、圧縮機保護のため圧縮機を停止します。",
                        "Low Voltage Alarm. Line voltage has been too low for at least 10 seconds; the compressor has shut down for protection.",
                        List.of("在设备输入端测量供电电压。", "检查插座、断路器、线径和端子压降。", "电压恢复后确认报警自动复位。"),
                        List.of("機器入力端で供給電圧を測定します。", "コンセント、遮断器、配線径、端子電圧降下を点検します。", "電圧復帰後に警報の自動復帰を確認します。"),
                        List.of("POWER_OR_WIRING_CONNECTION_FAILURE")),
                alarm(
                        "E8",
                        "TEMPERATURE_DISPLAY_FAULT",
                        "Cabinet Temperature Sensor Malfunction",
                        List.of("Cabinet Temperature Sensor Malfunction", "E8", "sensor has failed"),
                        "库内温度传感器失效时触发 E8，传感器更换后报警自动复位。",
                        "庫内温度センサが故障すると E8 が発生し、センサ交換後に警報が自動復帰します。",
                        "Cabinet Temperature Sensor Malfunction Alarm. The cabinet temperature sensor has failed.",
                        List.of("检查传感器连接和线束。", "按手册规格测量库温传感器。", "确认失效后更换传感器并验证显示。"),
                        List.of("センサ接続とハーネスを確認します。", "マニュアル仕様に従って庫内温度センサを測定します。", "故障確認後にセンサを交換し、表示を検証します。"),
                        List.of("SENSOR_WIRING_OR_CONTROL_DISPLAY_FAILURE")),
                alarm(
                        "E9",
                        "DEFROST_FAILURE_FROST",
                        "Defrost Temperature Sensor Malfunction",
                        List.of("Defrost Temperature Sensor Malfunction", "E9", "sensor has failed"),
                        "除霜温度传感器失效时触发 E9，传感器更换后报警自动复位。",
                        "除霜温度センサが故障すると E9 が発生し、センサ交換後に警報が自動復帰します。",
                        "Defrost Temperature Sensor Malfunction Alarm. The defrost temperature sensor has failed.",
                        List.of("检查除霜传感器连接。", "测量传感器阻值并与温度规格对照。", "确认失效后更换并执行除霜验证。"),
                        List.of("除霜センサの接続を確認します。", "センサ抵抗を測定し温度仕様と照合します。", "故障確認後に交換し、除霜動作を検証します。"),
                        List.of("DEFROST_SENSOR_OR_SAFETY_THERMOSTAT")),
                alarm(
                        "E10",
                        "COMPRESSOR_START_FAILURE",
                        "Communication Alarm",
                        List.of("Communication Alarm", "E10", "compressor delay"),
                        "双温机型的冷冻侧与冷藏侧控制板通信异常时触发 E10，通信恢复后自动复位。",
                        "デュアル温度モデルで冷凍側と冷蔵側の基板通信が異常になると E10 が発生し、通信復旧後に自動復帰します。",
                        "Communication Alarm. Freezer and refrigerator boards are not communicating properly.",
                        List.of("检查两块控制板之间的线束和连接器。", "确认控制板电源和 DIP 开关设置。", "恢复通信后确认报警自动复位。"),
                        List.of("両制御基板間のハーネスとコネクタを点検します。", "制御基板電源と DIP スイッチ設定を確認します。", "通信復旧後に警報の自動復帰を確認します。"),
                        List.of("CONTROL_BOARD_OR_COMPRESSOR_FAILURE")),
                alarm(
                        "CF",
                        "HIGH_PRESSURE_CONDENSATION",
                        "Clogged Filter Alarm",
                        List.of("Clogged Filter Alarm", "CF", "needs cleaning"),
                        "冷凝器过滤网需要清洁时触发 CF；频繁发生表示排气温度持续过高，继续运行可能损坏压缩机。",
                        "凝縮器フィルタの清掃が必要な場合 CF が発生します。頻発は吐出温度の継続的な上昇を示し、圧縮機損傷につながる可能性があります。",
                        "Clogged Filter Alarm. The condenser filter needs cleaning; frequent alarms indicate consistently high discharge temperature.",
                        List.of("清洁冷凝器过滤网。", "检查冷凝器和进出风路。", "等待传感器响应后按 RESET。", "频繁复发时检查风机、环境温度和高压回路。"),
                        List.of("凝縮器フィルタを清掃します。", "凝縮器と吸排気経路を確認します。", "センサ反応を待って RESET を押します。", "頻発時はファン、周囲温度、高圧回路を点検します。"),
                        List.of("CONDENSER_FILTER_CLOGGING")));
    }

    private UnitSpec alarm(
            String code,
            String problemType,
            String anchor,
            List<String> markers,
            String summary,
            String summaryJa,
            String quote,
            List<String> actions,
            List<String> actionsJa,
            List<String> candidates) {
        String title = "FH1-SSB " + code + " 报警定义";
        String titleJa = "FH1-SSB " + code + " 警報の定義";
        return new UnitSpec(
                "FAULT_DEFINITION:FH1-SSB:" + code,
                "FAULT_DEFINITION",
                problemType,
                code,
                markers,
                markers.subList(1, markers.size()),
                anchor,
                "II.E.ALARM." + code,
                title,
                titleJa,
                summary,
                summaryJa,
                quote,
                actions,
                actionsJa,
                List.of("报警原因未确认前不得旁路保护装置；电气测量由具备资质的维修人员执行。"),
                List.of("原因確認前に保護装置を短絡せず、電気測定は有資格のサービス担当者が実施してください。"),
                candidates,
                "FH1-SSB 显示 " + code + "。" + summary,
                "FH1-SSB に " + code + " が表示されます。" + summaryJa,
                String.join(" ", actions),
                String.join(" ", actionsJa));
    }
}
