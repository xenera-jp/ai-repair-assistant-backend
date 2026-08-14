package com.aifieldservice.repairassistant.service.knowledge.parser;

import java.util.List;

import org.springframework.stereotype.Component;

/** Hoshizaki FH1-AAC 服务手册的审查型解析 Profile。 */
@Component
public class Fh1AacServiceManualParser extends ReviewedServiceManualParser {

    private static final List<String> START_CANDIDATES = List.of(
            "POWER_OR_WIRING_CONNECTION_FAILURE",
            "START_RELAY_OR_CAPACITOR_FAILURE",
            "CONTROL_BOARD_OR_COMPRESSOR_FAILURE");
    private static final List<String> OVERLOAD_CANDIDATES = List.of(
            "COMPRESSOR_MECHANICAL_LOCK",
            "START_CAPACITOR_FAILURE",
            "START_RELAY_FAILURE");
    private static final List<String> HIGH_TEMP_CANDIDATES = List.of(
            "SETTING_AIRFLOW_OR_DOOR_SEAL",
            "REFRIGERANT_CIRCUIT_CAPACITY_LOSS",
            "SENSOR_FAN_OR_CONTROL_FAILURE");
    private static final List<String> TOO_COLD_CANDIDATES = List.of(
            "CABINET_THERMISTOR_FAILURE",
            "COMPRESSOR_RELAY_CONTACT_STUCK",
            "TEMPERATURE_CONTROL_BOARD_FAILURE");
    private static final List<String> DEFROST_CANDIDATES = List.of(
            "DEFROST_SENSOR_OR_SAFETY_THERMOSTAT",
            "DEFROST_HEATER_FAILURE",
            "DEFROST_CONTROL_OR_FREQUENCY_SETTING");
    private static final List<String> HIGH_PRESSURE_CANDIDATES = List.of(
            "CONDENSER_FILTER_CLOGGING",
            "REFRIGERANT_CHARGE_OR_CIRCUIT_ABNORMALITY",
            "HIGH_PRESSURE_SWITCH_ABNORMALITY");
    private static final List<String> DISPLAY_CANDIDATES = List.of(
            "SENSOR_WIRING_OR_CONTROL_DISPLAY_FAILURE");
    private static final List<String> DRAIN_CANDIDATES = List.of(
            "HUMIDITY_OR_WARM_PRODUCT_LOAD",
            "DOOR_OR_EVAPORATOR_SEAL_LEAK");
    private static final List<String> NOISE_CANDIDATES = List.of(
            "LOOSE_FASTENER_OR_MOUNT",
            "FAN_MOTOR_OR_BLADE",
            "COMPRESSOR_OR_RELAY_NOISE");

    @Override
    protected String model() {
        return "FH1-AAC";
    }

    @Override
    protected String parserVersion() {
        return "PDFBOX_FH1_AAC_V2";
    }

    @Override
    protected List<String> identityMarkers() {
        return List.of("FH1-AAC", "SERVICE MANUAL", "Diagnosis Chart");
    }

    @Override
    protected List<UnitSpec> unitSpecs() {
        return List.of(
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:COMPRESSOR_NO_CURRENT",
                        "COMPRESSOR_START_FAILURE",
                        "",
                        List.of("Compressor will", "Ground Fault Circuit", "Control Module"),
                        List.of("Open coil winding", "Compressor Relay"),
                        "Compressor will",
                        "III.A.[1]",
                        "FH1-AAC 压缩机无电流不启动",
                        "FH1-AAC 圧縮機が始動しない（電流なし）",
                        "手册要求依次检查电源、插头、漏电保护器、变压器、配线、保护开关、热敏电阻、控制模块、继电器和压缩机绕组。",
                        "電源、プラグ、漏電遮断器、変圧器、配線、保護スイッチ、サーミスタ、制御モジュール、リレー、圧縮機巻線を順に点検します。",
                        "Compressor will not start—no current draw. Possible causes include power supply, cord and plug, ground fault circuit interrupter, transformer, wiring, control module, compressor relay, and compressor.",
                        List.of("确认主电源处于 ON 并测量输入电压。", "检查插头、漏电保护器和接线端子。", "检查变压器、控制配线及保护开关导通。", "测量继电器和压缩机绕组后再决定更换部件。"),
                        List.of("主電源が ON であることを確認し、入力電圧を測定します。", "プラグ、漏電遮断器、配線端子を点検します。", "変圧器、制御配線、保護スイッチの導通を確認します。", "リレーと圧縮機巻線を測定してから交換を判断します。"),
                        START_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:COMPRESSOR_OVERLOAD",
                        "COMPRESSOR_OVERLOAD_TRIP",
                        "",
                        List.of("draws", "trips on overload", "Start Capacitor"),
                        List.of("Locked rotor", "Too low"),
                        "draws",
                        "III.A.[2-3]",
                        "FH1-AAC 压缩机过载跳闸",
                        "FH1-AAC 圧縮機の過負荷トリップ",
                        "压缩机有电流但过载跳闸时，应检查电压、启动继电器、启动电容及压缩机是否锁转；间歇跳闸还应检查冷凝散热和冷媒回路。",
                        "電流は流れるが過負荷で停止する場合、電圧、始動リレー、始動コンデンサ、圧縮機ロックを確認し、間欠停止では凝縮放熱と冷媒回路も点検します。",
                        "Compressor will not run—draws current and trips on overload. Check voltage, start relay, compressor locked rotor, and start capacitor.",
                        List.of("测量供电电压是否在规格范围。", "检查启动继电器触点及线圈。", "检查启动电容。", "确认压缩机是否机械锁转；间歇跳闸时清洁冷凝器和过滤网。"),
                        List.of("供給電圧が規格内か測定します。", "始動リレーの接点とコイルを点検します。", "始動コンデンサを点検します。", "圧縮機ロックを確認し、間欠トリップでは凝縮器とフィルタを清掃します。"),
                        OVERLOAD_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:CABINET_HIGH_TEMP",
                        "CABINET_HIGH_TEMP",
                        "",
                        List.of("Cabinet temperature", "Setpoint", "Air Filter", "Condenser"),
                        List.of("Not sealing", "Refrigerant", "Thermistor"),
                        "Setpoint",
                        "III.A.[4-5]",
                        "FH1-AAC 库内温度过高",
                        "FH1-AAC 庫内温度が高い",
                        "库温过高时，手册要求检查设定值、门封和开门情况、除霜次数、冷媒泄漏、风机、过滤网、冷凝器、热敏电阻及控制模块。",
                        "庫内温度が高い場合、設定値、ドアシール、開放時間、除霜回数、冷媒漏れ、ファン、フィルタ、凝縮器、サーミスタ、制御モジュールを確認します。",
                        "Cabinet temperature too high. Possible causes include incorrect setpoint, door not sealing, insufficient defrost, refrigerant leak, fan motor, clogged air filter, dirty condenser, thermistor, and control module.",
                        List.of("核对温度设定值。", "检查门封、开门时间和风路。", "清洁过滤网和冷凝器并确认风机运行。", "检查除霜设置和热敏电阻。", "最后测量冷媒回路并检查控制模块。"),
                        List.of("温度設定値を確認します。", "ドアシール、開放時間、風路を点検します。", "フィルタと凝縮器を清掃し、ファン運転を確認します。", "除霜設定とサーミスタを確認します。", "最後に冷媒回路を測定し、制御モジュールを点検します。"),
                        HIGH_TEMP_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:CABINET_TOO_COLD",
                        "CABINET_TOO_COLD",
                        "",
                        List.of("Cabinet temperature", "too low", "contacts", "welded"),
                        List.of("Thermistor", "Compressor Relay"),
                        "too low",
                        "III.A.[7]",
                        "FH1-AAC 库内温度过低",
                        "FH1-AAC 庫内温度が低すぎる",
                        "手册将热敏电阻失效、压缩机继电器触点粘连和控制模块故障列为库内温度过低的主要原因。",
                        "サーミスタ不良、圧縮機リレー接点溶着、制御モジュール不良が主な原因として示されています。",
                        "Cabinet temperature too low. Possible causes: defective thermistor, compressor relay contacts welded, or defective control module.",
                        List.of("比较显示温度与独立温度计实测值。", "测量热敏电阻。", "确认停机命令后继电器触点是否仍闭合。", "输入和输出均正常后再判断控制模块。"),
                        List.of("表示温度と独立温度計の実測値を比較します。", "サーミスタを測定します。", "停止指令後もリレー接点が閉じていないか確認します。", "入出力確認後に制御モジュールを判断します。"),
                        TOO_COLD_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:DEFROST_INCOMPLETE",
                        "DEFROST_FAILURE_FROST",
                        "",
                        List.of("not defrost completely", "Defrost Heaters", "Safety Defrost"),
                        List.of("Defrost Thermistor", "turning off"),
                        "not defrost completely",
                        "III.A.[8-9]",
                        "FH1-AAC 除霜不完全",
                        "FH1-AAC デフロスト不完全",
                        "蒸发器除霜不完全或除霜时间过长时，应检查除霜热敏电阻、除霜频率、加热器、安全除霜温控器和控制模块。",
                        "蒸発器の除霜不良または長時間化では、除霜サーミスタ、除霜頻度、ヒータ、安全サーモ、制御モジュールを点検します。",
                        "Evaporator does not defrost completely. Check defrost thermistor, defrost frequency, defrost heaters, and safety defrost thermostat.",
                        List.of("检查除霜周期和最近一次除霜是否完整。", "测量除霜热敏电阻。", "测量除霜加热器阻值和供电。", "检查安全除霜温控器导通。", "确认控制模块除霜输出。"),
                        List.of("除霜周期と直近の除霜完了状態を確認します。", "除霜サーミスタを測定します。", "除霜ヒータの抵抗と電源を測定します。", "安全除霜サーモの導通を確認します。", "制御モジュールの除霜出力を確認します。"),
                        DEFROST_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:TEMPERATURE_DISPLAY",
                        "TEMPERATURE_DISPLAY_FAULT",
                        "",
                        List.of("display indicator", "Control Module"),
                        List.of("not illuminate properly"),
                        "display indicator",
                        "III.A.[6]",
                        "FH1-AAC 温度显示异常",
                        "FH1-AAC 温度表示異常",
                        "库温显示无法正常点亮时，手册指向控制模块及其供电、连接状态。",
                        "庫内温度表示が正常に点灯しない場合、制御モジュールとその電源・接続状態を確認します。",
                        "Cabinet temperature display indicator does not illuminate properly. Possible cause: defective control module.",
                        List.of("检查显示和控制模块连接。", "测量控制模块输入电源。", "确认输入正常后检查控制模块输出。"),
                        List.of("表示部と制御モジュールの接続を確認します。", "制御モジュールの入力電源を測定します。", "入力正常を確認後、制御出力を点検します。"),
                        DISPLAY_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:CONDENSATE_OVERFLOW",
                        "DRAIN_OVERFLOW",
                        "",
                        List.of("Condensate", "water overflow", "Cabinet Contents"),
                        List.of("fryer, steamer", "Poor sealing"),
                        "Condensate",
                        "III.A.[10]",
                        "FH1-AAC 冷凝水溢出",
                        "FH1-AAC ドレン水あふれ",
                        "冷凝水溢出通常与大量温热未覆盖物品、高湿热源、蒸发器或门封密封不良以及极端环境和频繁开门有关。",
                        "ドレン水あふれは、温かく湿った未包装品、高湿度源、蒸発器・ドアシール不良、過酷な環境や頻繁な開閉と関連します。",
                        "Condensate water overflow. Check warm moist uncovered product, high humidity source, seals around evaporator and door gaskets, and door-opening conditions.",
                        List.of("确认是否放入大量温热、未覆盖物品。", "检查设备是否靠近油炸机、蒸汽设备等高湿热源。", "检查蒸发器周边和门封密封。", "改善环境湿度和开门条件。"),
                        List.of("温かく湿った未包装品の大量投入を確認します。", "フライヤーや蒸気機器など高湿度源との距離を確認します。", "蒸発器周辺とドアシールを点検します。", "環境湿度と扉開閉条件を改善します。"),
                        DRAIN_CANDIDATES),
                spec(
                        "REPAIR_PROCEDURE:FH1-AAC:ABNORMAL_NOISE",
                        "ABNORMAL_NOISE",
                        "",
                        List.of("Abnormal Noise", "Fasteners", "Fan blade loose"),
                        List.of("Relay", "Chattering"),
                        "Abnormal Noise",
                        "III.A.[11]",
                        "FH1-AAC 异常噪音",
                        "FH1-AAC 異常音",
                        "异常噪音可能来自紧固件松动、压缩机安装或液击、风扇叶片或电机异常，以及继电器抖动。",
                        "異常音は、締結部の緩み、圧縮機取付・液戻り、ファン羽根・モータ不良、リレーのチャタリングが原因となります。",
                        "Abnormal Noise. Check loose fasteners, compressor mounting or floodback, loose fan blade or defective motor, and relay chattering.",
                        List.of("紧固松动部件。", "检查压缩机安装胶垫及液击迹象。", "检查风扇叶片固定和电机。", "确认继电器是否抖动。"),
                        List.of("緩んだ締結部を締め付けます。", "圧縮機マウントと液戻りの兆候を確認します。", "ファン羽根の固定とモータを点検します。", "リレーのチャタリングを確認します。"),
                        NOISE_CANDIDATES),
                spec(
                        "FAULT_DEFINITION:FH1-AAC:HIGH_PRESSURE_SWITCH",
                        "HIGH_PRESSURE_CONDENSATION",
                        "",
                        List.of("E. Safety Devices", "Pressure Switch", "high-side"),
                        List.of("shut down the compressor", "reset automatically"),
                        "Pressure Switch",
                        "II.E.1",
                        "FH1-AAC 高压保护开关",
                        "FH1-AAC 高圧保護スイッチ",
                        "高压侧压力超过预设上限时，压力开关切断压缩机继电器电源并停止压缩机；压力恢复后开关自动复位。",
                        "高圧側圧力が設定上限を超えると圧力スイッチが圧縮機リレー電源を遮断し、圧縮機を停止します。圧力低下後は自動復帰します。",
                        "When pressure on the high-side is above a preset limit, the pressure switch interrupts power to the compressor relay and shuts down the compressor. The switch resets automatically.",
                        List.of("检查冷凝器、过滤网、风路和环境温度。", "确认冷凝风扇运行。", "测量高压侧压力并检查冷媒充注和回路限制。", "最后检查压力开关触点。"),
                        List.of("凝縮器、フィルタ、風路、周囲温度を確認します。", "凝縮器ファンの運転を確認します。", "高圧側圧力を測定し、冷媒充填量と回路閉塞を確認します。", "最後に圧力スイッチ接点を確認します。"),
                        HIGH_PRESSURE_CANDIDATES));
    }

    private UnitSpec spec(
            String key,
            String problemType,
            String errorCode,
            List<String> pageMarkers,
            List<String> requiredFacts,
            String anchor,
            String section,
            String title,
            String titleJa,
            String summary,
            String summaryJa,
            String quote,
            List<String> actions,
            List<String> actionsJa,
            List<String> candidates) {
        return new UnitSpec(
                key,
                key.substring(0, key.indexOf(':')),
                problemType,
                errorCode,
                pageMarkers,
                requiredFacts,
                anchor,
                section,
                title,
                titleJa,
                summary,
                summaryJa,
                quote,
                actions,
                actionsJa,
                List.of("仅限具备资质的维修人员操作；带电检查和冷媒回路作业必须遵守手册安全要求。"),
                List.of("有資格のサービス担当者のみが作業し、通電点検と冷媒回路作業はマニュアルの安全要件に従ってください。"),
                candidates,
                title + "。" + summary,
                titleJa + "。" + summaryJa,
                String.join(" ", actions),
                String.join(" ", actionsJa));
    }
}
