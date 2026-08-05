package com.aifieldservice.repairassistant.knowledge;

import java.util.List;

import org.springframework.stereotype.Component;

/** Hoshizaki HNC-120AA 服务诊断表的审查型解析 Profile。 */
@Component
public class Hnc120AaServiceManualParser extends ReviewedServiceManualParser {

    @Override
    protected String model() {
        return "HNC-120AA";
    }

    @Override
    protected String parserVersion() {
        return "PDFBOX_HNC_120AA_V2";
    }

    @Override
    protected List<String> identityMarkers() {
        return List.of("HNC-120AA", "SERVICE MANUAL", "SERVICE DIAGNOSIS");
    }

    @Override
    protected List<UnitSpec> unitSpecs() {
        List<String> page = List.of(
                "12. SERVICE DIAGNOSIS",
                "Showcase will not",
                "Poor cooling",
                "Dry foods",
                "Frosting");
        return List.of(
                spec(
                        "EQUIPMENT_NO_START_HNC",
                        "SHOWCASE_NO_START",
                        "Showcase will not",
                        page,
                        List.of("Ground Fault", "Supply voltage too low", "Motor Protector"),
                        "HNC-120AA 展示柜无法启动",
                        "HNC-120AA ショーケースが始動しない",
                        "展示柜无法启动时，应检查漏电保护器、插头、供电电压、墙面插座、电路开路或接触不良以及风机电机保护器。",
                        "ショーケースが始動しない場合、漏電遮断器、プラグ、供給電圧、コンセント、回路断線・接触不良、ファンモータ保護器を点検します。",
                        "Showcase will not start. Possible causes include GFCI off, unplugged power, low supply voltage, no wall outlet power, open circuit or bad contacts, and motor protector trip.",
                        List.of("确认漏电保护器处于 ON。", "检查插头、墙面插座及断路器。", "测量供电是否为 115V±10%。", "检查电路连接和接点。", "改善通风并复位风机电机保护器。"),
                        List.of("漏電遮断器が ON であることを確認します。", "プラグ、コンセント、遮断器を点検します。", "供給電圧が 115V±10% か測定します。", "回路接続と接点を確認します。", "換気を改善しファンモータ保護器を復帰します。"),
                        List.of(
                                "POWER_SUPPLY_OR_GFCI",
                                "OPEN_CIRCUIT_OR_BAD_CONTACT",
                                "FAN_MOTOR_PROTECTOR_TRIP")),
                spec(
                        "COOLING_INSUFFICIENT_HNC",
                        "POOR_COOLING",
                        "Poor cooling",
                        page,
                        List.of("Gas leaks", "Condenser", "Ambient temperature"),
                        "HNC-120AA 冷却能力不足",
                        "HNC-120AA 冷却不良",
                        "冷却不良可能来自冷媒泄漏、风机故障、冷凝器或过滤网堵塞、进风受阻、阳光或热源、频繁开门、装载过多以及环境温度超过 27°C。",
                        "冷却不良は、冷媒漏れ、ファン不良、凝縮器・フィルタ目詰まり、吸気阻害、直射日光・熱源、頻繁な扉開閉、過積載、周囲温度27°C超過が原因となります。",
                        "Poor cooling performance. Check gas leaks, fan motor, condenser and air filter, blocked condenser inlet, direct sunlight, heat sources, door use, food loading, and ambient temperature.",
                        List.of("清洁冷凝器和过滤网并确保进风无遮挡。", "确认风机电机运行。", "改善日照、热源、装载和开门条件。", "环境条件正常后执行冷媒泄漏检查。"),
                        List.of("凝縮器とフィルタを清掃し、吸気口の障害を除きます。", "ファンモータの運転を確認します。", "直射日光、熱源、積載、扉開閉条件を改善します。", "環境条件正常化後に冷媒漏れを点検します。"),
                        List.of(
                                "CONDENSER_OR_FILTER_BLOCKAGE",
                                "FAN_OR_AIRFLOW_FAILURE",
                                "REFRIGERANT_OR_ENVIRONMENT_CAPACITY_LOSS")),
                spec(
                        "CABINET_DRYNESS_HNC",
                        "DRY_FOODS",
                        "Dry foods",
                        page,
                        List.of("previous day", "Foods have been stored"),
                        "HNC-120AA 食品干燥",
                        "HNC-120AA 食品の乾燥",
                        "食品从前一天持续存放或存放时间过长会导致干燥，手册建议向用户说明展示柜的使用特性和正确存放方式。",
                        "前日から継続して保管した食品や長時間保管した食品は乾燥しやすく、ショーケースの特性と正しい保管方法を案内します。",
                        "Dry foods. Foods have been stored from the previous day or for a long time.",
                        List.of("确认食品连续存放时间。", "说明展示柜适合的存放周期。", "调整补货和轮换方式，避免长时间暴露存放。"),
                        List.of("食品の連続保管時間を確認します。", "ショーケースに適した保管期間を案内します。", "補充・入替方法を見直し、長時間の露出保管を避けます。"),
                        List.of("EXCESSIVE_STORAGE_DURATION")),
                spec(
                        "ENVIRONMENTAL_FROST_HNC",
                        "FROSTING",
                        "Frosting",
                        page,
                        List.of("Relative humidity", "Doors opened"),
                        "HNC-120AA 环境性结霜",
                        "HNC-120AA 環境条件による着霜",
                        "外部结霜通常与相对湿度超过 60% 有关，内部结霜通常与频繁开门或长时间开门有关。",
                        "外部着霜は相対湿度60%超過、内部着霜は頻繁な扉開閉または長時間開放と関連します。",
                        "Frosting. Exterior: relative humidity exceeding 60%. Interior: doors opened too frequently or left open.",
                        List.of("测量环境相对湿度。", "确认开门频率和持续时间。", "改善环境和使用条件。", "使用软布擦除过量结霜。"),
                        List.of("周囲相対湿度を測定します。", "扉の開閉頻度と開放時間を確認します。", "環境条件と使用方法を改善します。", "柔らかい布で過剰な霜を拭き取ります。"),
                        List.of(
                                "HIGH_AMBIENT_HUMIDITY",
                                "FREQUENT_OR_LONG_DOOR_OPENING")));
    }

    private UnitSpec spec(
            String problemType,
            String suffix,
            String anchor,
            List<String> pageMarkers,
            List<String> facts,
            String title,
            String titleJa,
            String summary,
            String summaryJa,
            String quote,
            List<String> actions,
            List<String> actionsJa,
            List<String> candidates) {
        return new UnitSpec(
                "REPAIR_PROCEDURE:HNC-120AA:" + suffix,
                "REPAIR_PROCEDURE",
                problemType,
                "",
                pageMarkers,
                facts,
                anchor,
                "12.SERVICE_DIAGNOSIS." + suffix,
                title,
                titleJa,
                summary,
                summaryJa,
                quote,
                actions,
                actionsJa,
                List.of("断电后再进行拆卸和清洁；冷媒与带电检查必须由具备资质的维修人员执行。"),
                List.of("分解・清掃前に電源を切り、冷媒作業と通電点検は有資格のサービス担当者が実施してください。"),
                candidates,
                title + "。" + summary,
                titleJa + "。" + summaryJa,
                String.join(" ", actions),
                String.join(" ", actionsJa));
    }
}
