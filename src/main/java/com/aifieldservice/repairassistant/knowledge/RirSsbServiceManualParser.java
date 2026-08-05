package com.aifieldservice.repairassistant.knowledge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualDocument;
import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualUnit;
import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.PageText;
import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.SourceRegion;

/**
 * 首个服务手册解析 Profile：Hoshizaki RIR1-SSB service manual。
 *
 * <p>这里故意没有声称能够解析任意 PDF。PDFBox 负责确定性的页面文本提取，
 * Profile 负责识别这本手册中已经审查过的章节结构，并把完整章节转换成知识单元。
 * 当手册布局或关键语义发生变化时，解析会明确失败，而不是静默生成错误知识。
 */
@Component
public class RirSsbServiceManualParser implements ServiceManualParser {

    private static final String MODEL = "RIR1-SSB";
    private static final String PROBLEM_TYPE = "HIGH_PRESSURE_CONDENSATION";
    private static final String PARSER_VERSION = "PDFBOX_RIR1_SSB_V2";
    private static final Pattern PRINTED_PAGE = Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*$");

    @Override
    public boolean supports(Path path) {
        String name = path.getFileName().toString().toUpperCase(Locale.ROOT);
        return name.contains(MODEL)
                && name.contains("SERVICE MANUAL")
                && name.endsWith(".PDF");
    }

    /**
     * 从原 PDF 中定位三个相互独立、可追溯的 E4 知识单元：
     * 错误定义、官方检查流程和诊断原因表。
     */
    @Override
    public ManualDocument parse(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            List<PageText> pages = extractPages(document);
            requireDocumentIdentity(pages);

            PageText alarmPage = findPage(
                    pages,
                    "LED Lights and Alarm Safeties Chart",
                    "High-Pressure Alarm",
                    "E4");
            PageText procedurePage = findPage(
                    pages,
                    "Clogged Filter Thermostat and High-Pressure Switch",
                    "Diagnosis: High-Pressure Switch",
                    "E4 alarm occurs");
            PageText chartPage = findPage(
                    pages,
                    "E. Diagnostic Chart",
                    "High-Pressure Switch",
                    "Refrigerant overcharge");

            // 浏览器 PDF 文本层在复杂表格中可能与画布坐标发生偏移。这里直接从
            // 原始 PDF 字形坐标提取证据区域，前端按页面比例绘制高亮框。
            SourceRegion alarmRegion = locateSourceRegion(
                    document, alarmPage.pdfPageIndex(), "High-Pressure Alarm");
            SourceRegion procedureRegion = locateSourceRegion(
                    document, procedurePage.pdfPageIndex(), "Diagnosis: High-Pressure Switch");
            SourceRegion chartRegion = locateSourceRegion(
                    document, chartPage.pdfPageIndex(), "High-Pressure Switch");

            // 这些断言把“版式命中”提升为“关键事实命中”。若来源换版导致内容变化，
            // 导入任务会进入失败/重试，而不会继续发布一条看似正常的旧结论。
            requireText(alarmPage, "3 or more times in", "5 times in");
            requireText(procedurePage, "air filter and condenser are clean", "ConFM is operating");
            requireText(chartPage, "Dirty condenser", "Ambient temperature too warm",
                    "Refrigerant lines or components restricted");

            List<String> allCandidates = List.of(
                    "CONDENSER_FILTER_CLOGGING",
                    "REFRIGERANT_CHARGE_OR_CIRCUIT_ABNORMALITY",
                    "HIGH_PRESSURE_SWITCH_ABNORMALITY");

            List<ManualUnit> units = List.of(
                    new ManualUnit(
                            "FAULT_DEFINITION:RIR1-SSB:E4",
                            "FAULT_DEFINITION",
                            PROBLEM_TYPE,
                            "E4",
                            "RIR1-SSB E4 高压报警定义",
                            "RIR1-SSB E4 高圧警報の定義",
                            "E4 表示压缩机排气压力超出正常范围；高压开关在 1 小时内触发 3 次以上时产生报警，触发 5 次后压缩机停止且不会自动重启。",
                            "E4 は圧縮機の吐出圧力が正常範囲外であることを示します。高圧スイッチが1時間以内に3回以上作動すると警報が発生し、5回作動すると圧縮機は停止して自動再起動しません。",
                            "High-Pressure Alarm\n"
                                    + "Compressor discharge pressure is outside normal operating range. "
                                    + "High-pressure switch has been triggered 3 or more times in 1 hour.\n"
                            + "If the high-pressure switch trips 5 times in 1 hour, compressor "
                                    + "stops and will not restart.",
                            "High-Pressure Alarm",
                            alarmRegion,
                            List.of(
                                    "确认高压开关恢复后，在控制板上按 RESET 清除或暂时静音报警。",
                                    "完成原因检查后，由维修人员按控制板上的 ALARM RESET 执行维修复位。"),
                            List.of(
                                    "高圧スイッチが復帰したことを確認し、制御基板の RESET で警報を解除または一時消音します。",
                                    "原因点検の完了後、サービス担当者が制御基板の ALARM RESET で保守リセットを実施します。"),
                            List.of("高压开关 1 小时内触发 5 次后，压缩机停止且不会自动重启。"),
                            List.of("高圧スイッチが1時間以内に5回作動すると、圧縮機は停止し自動再起動しません。"),
                            allCandidates,
                            alarmPage,
                            "II.E.3",
                            "RIR1-SSB E4 高压报警，设备不制冷，压缩机高压保护，High-Pressure Alarm。",
                            "RIR1-SSB E4 高圧警報、冷却不良、圧縮機高圧保護、High-Pressure Alarm。",
                            "E4 的定义、触发条件和复位规则；后续必须检查冷凝散热、高压回路与高压开关。",
                            "E4 の定義、作動条件、リセット規則。凝縮放熱、高圧回路、高圧スイッチの点検が必要です。"),
                    new ManualUnit(
                            "REPAIR_PROCEDURE:RIR1-SSB:E4:CHECK",
                            "REPAIR_PROCEDURE",
                            PROBLEM_TYPE,
                            "E4",
                            "RIR1-SSB E4 官方检查流程",
                            "RIR1-SSB E4 公式点検手順",
                            "官方流程要求先排除过滤网、冷凝器和冷凝风扇问题，再确认控制板提供给高压开关的 5VDC 电源。",
                            "公式手順では、フィルタ、凝縮器、凝縮器ファンの異常を先に除外し、その後に制御基板から高圧スイッチへ供給される 5VDC を確認します。",
                            "Diagnosis: High-Pressure Switch: If HPS trips 3 times in 1-hour, E4 alarm "
                                    + "occurs on CB. Check that the air filter and condenser are clean. "
                                    + "Next, check that the ConFM is operating.",
                            "Diagnosis: High-Pressure Switch",
                            procedureRegion,
                            List.of(
                                    "检查空气过滤网和冷凝器是否清洁、是否存在堵塞。",
                                    "确认冷凝风扇已通电且叶片能够自由转动。",
                                    "确认设备安装环境满足手册规定的通风与环境温度要求。",
                                    "检查控制板 K6-6 与 K5-2 之间是否有 5VDC；缺失时检查控制板。",
                                    "结合实测压力判断高压开关是正常保护动作还是本体异常。"),
                            List.of(
                                    "エアフィルタと凝縮器が清潔で、目詰まりしていないことを確認します。",
                                    "凝縮器ファンに通電され、羽根が自由に回転することを確認します。",
                                    "設置環境の換気と周囲温度がサービスマニュアルの条件を満たすことを確認します。",
                                    "制御基板の K6-6 と K5-2 間に 5VDC があることを確認し、ない場合は制御基板を点検します。",
                                    "実測圧力と照合し、高圧スイッチが正常な保護作動か本体異常かを判断します。"),
                            List.of("不得在未确认散热条件和实测压力前旁路或直接判定高压开关损坏。"),
                            List.of("放熱条件と実測圧力を確認する前に、高圧スイッチを短絡したり故障と断定したりしないでください。"),
                            allCandidates,
                            procedurePage,
                            "III.D",
                            "RIR1-SSB E4 高压报警检查，过滤网堵塞，冷凝器脏堵，冷凝风扇停止，高压开关检查。",
                            "RIR1-SSB E4 高圧警報点検、フィルタ目詰まり、凝縮器汚れ、凝縮器ファン停止、高圧スイッチ点検。",
                            "先检查过滤网和冷凝器，再检查冷凝风扇、环境条件、5VDC 控制电源与高压开关。",
                            "フィルタと凝縮器を先に確認し、次に凝縮器ファン、設置条件、5VDC 制御電源、高圧スイッチを点検します。"),
                    new ManualUnit(
                            "REPAIR_PROCEDURE:RIR1-SSB:E4:CAUSES",
                            "REPAIR_PROCEDURE",
                            PROBLEM_TYPE,
                            "E4",
                            "RIR1-SSB E4 诊断原因表",
                            "RIR1-SSB E4 原因診断表",
                            "手册将冷凝器脏堵、环境温度过高、冷凝风扇不运行、冷媒过量、管路或部件受限以及接点不良列为 E4 的可能原因。",
                            "サービスマニュアルでは、凝縮器の汚れ、周囲温度過高、凝縮器ファン停止、冷媒過充填、配管・部品の閉塞、接点不良を E4 の原因候補として示しています。",
                            "High-Pressure Switch (E4 alarm, 3 or more pressure trips in 1 hour, "
                                    + "6 beep alarm): Dirty condenser; ambient temperature too warm; "
                                    + "condenser fan motor not operating; refrigerant overcharge; "
                                    + "refrigerant lines or components restricted; bad contacts.",
                            "High-Pressure Switch",
                            chartRegion,
                            List.of(
                                    "先确认冷凝器、过滤网和风路状态。",
                                    "确认环境温度与安装间距符合要求。",
                                    "确认冷凝风扇运行状态。",
                                    "散热正常后再测量系统压力并核对冷媒充注及回路阻塞。",
                                    "压力与开关输出不一致时，再检查高压开关及接点。"),
                            List.of(
                                    "最初に凝縮器、フィルタ、風路の状態を確認します。",
                                    "周囲温度と設置間隔が規定を満たすことを確認します。",
                                    "凝縮器ファンの運転状態を確認します。",
                                    "放熱が正常になってからシステム圧力を測定し、冷媒充填量と回路閉塞を確認します。",
                                    "実測圧力とスイッチ出力が一致しない場合、高圧スイッチと接点を点検します。"),
                            List.of("冷媒回路检查必须在散热条件确认之后进行。"),
                            List.of("冷媒回路の点検は、放熱条件を確認した後に実施してください。"),
                            allCandidates,
                            chartPage,
                            "III.E.1.8",
                            "RIR1-SSB 不制冷 E4，冷凝器脏堵，环境过热，风扇不运行，冷媒过量，管路受限。",
                            "RIR1-SSB 冷却不良 E4、凝縮器汚れ、周囲温度過高、ファン停止、冷媒過充填、回路閉塞。",
                            "E4 可能原因及排查顺序：散热和风路优先，其次测量冷媒回路，最后核对高压开关。",
                            "E4 の原因候補と点検順序：放熱と風路を優先し、次に冷媒回路を測定し、最後に高圧スイッチを確認します。"));

            return new ManualDocument(
                    path.getFileName().toString(),
                    "SERVICE_MANUAL:" + MODEL,
                    PARSER_VERSION,
                    "Hoshizaki",
                    MODEL,
                    document.getNumberOfPages(),
                    units);
        }
    }

    private List<PageText> extractPages(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        // 表格的列序依赖文字坐标；按视觉位置排序比 PDF 内部对象顺序稳定。
        stripper.setSortByPosition(true);
        List<PageText> pages = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String raw = stripper.getText(document);
            pages.add(new PageText(page, printedPageLabel(raw), normalize(raw)));
        }
        return pages;
    }

    private void requireDocumentIdentity(List<PageText> pages) {
        String frontMatter = pages.stream()
                .limit(8)
                .map(PageText::text)
                .reduce("", (left, right) -> left + "\n" + right);
        if (!frontMatter.contains(MODEL) || !frontMatter.contains("SERVICE MANUAL")) {
            throw new IllegalArgumentException("PDF is not the reviewed RIR1-SSB service manual");
        }
    }

    private SourceRegion locateSourceRegion(
            PDDocument document,
            int pdfPageIndex,
            String sourceAnchor) throws IOException {
        AnchorPositionStripper stripper = new AnchorPositionStripper(sourceAnchor);
        stripper.setSortByPosition(true);
        stripper.setStartPage(pdfPageIndex);
        stripper.setEndPage(pdfPageIndex);
        stripper.getText(document);
        SourceRegion region = stripper.region();
        if (region == null) {
            throw new IllegalArgumentException(
                    "Source anchor not found on PDF page %d: %s"
                            .formatted(pdfPageIndex, sourceAnchor));
        }

        var cropBox = document.getPage(pdfPageIndex - 1).getCropBox();
        return new SourceRegion(
                region.x(),
                region.y(),
                region.width(),
                region.height(),
                cropBox.getWidth(),
                cropBox.getHeight());
    }

    private PageText findPage(List<PageText> pages, String... required) {
        return pages.stream()
                .filter(page -> containsAll(page.text(), required))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Required manual section not found: " + String.join(" / ", required)));
    }

    private void requireText(PageText page, String... required) {
        if (!containsAll(page.text(), required)) {
            throw new IllegalArgumentException(
                    "Manual page " + page.pdfPageIndex() + " failed semantic validation");
        }
    }

    private boolean containsAll(String text, String... required) {
        for (String marker : required) {
            if (!text.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String text) {
        return text
                .replace('\u00a0', ' ')
                .replace('\u2011', '-')
                .replace('\u2013', '-')
                .replaceAll("(?m)^Downloaded from .*manuals search engine\\s*$", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String printedPageLabel(String raw) {
        Matcher matcher = PRINTED_PAGE.matcher(raw);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }

    /**
     * 在 PDFBox 已按视觉顺序组织的一行文字中寻找来源短语，并记录该行字形的包围盒。
     * 服务手册 Profile 的 anchor 都是经过测试的独立标题/表格单元，因此使用整行包围盒
     * 比在浏览器端重新猜测字符位置更稳定。
     */
    private static final class AnchorPositionStripper extends PDFTextStripper {

        private final String anchor;
        private SourceRegion region;

        private AnchorPositionStripper(String anchor) throws IOException {
            this.anchor = normalizeAnchor(anchor);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (region == null
                    && normalizeAnchor(text).contains(anchor)
                    && !positions.isEmpty()) {
                double minX = Double.MAX_VALUE;
                double minTop = Double.MAX_VALUE;
                double maxX = Double.MIN_VALUE;
                double maxBottom = Double.MIN_VALUE;
                for (TextPosition position : positions) {
                    double x = position.getXDirAdj();
                    double bottom = position.getYDirAdj();
                    double top = bottom - position.getHeightDir();
                    minX = Math.min(minX, x);
                    minTop = Math.min(minTop, top);
                    maxX = Math.max(maxX, x + position.getWidthDirAdj());
                    maxBottom = Math.max(maxBottom, bottom);
                }
                region = new SourceRegion(
                        minX,
                        minTop,
                        maxX - minX,
                        maxBottom - minTop,
                        0,
                        0);
            }
            super.writeString(text, positions);
        }

        private SourceRegion region() {
            return region;
        }

        private static String normalizeAnchor(String value) {
            return value
                    .replace('\u00a0', ' ')
                    .replace('\u2011', '-')
                    .replace('\u2013', '-')
                    .replaceAll("\\s+", " ")
                    .strip();
        }
    }

}
