package com.aifieldservice.repairassistant.diagnosis;

import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemType;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstanding;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstandingRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.UnderstoodField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.aifieldservice.repairassistant.knowledge.ProblemCatalogService;
import com.aifieldservice.repairassistant.knowledge.ProblemCatalogService.ProblemMatch;

import tools.jackson.databind.ObjectMapper;

/**
 * 把用户自然语言转换成标准化的维修问题模型。
 *
 * <p>V1 使用“规则抽取 + 受控问题分类”，而不是让 LLM 自由生成字段：
 * 型号来自已登记问题类型的 model scope，错误码和测量值由正则提取，
 * 问题类型由 {@link ProblemCatalogService} 评分。这样关键字段稳定、可解释、可测试。
 */
@Service
public class ProblemUnderstandingService {

    private static final Pattern ERROR_CODE =
            Pattern.compile("(?i)(?<![A-Z0-9])E\\d+(?![A-Z0-9])");
    private static final Pattern TEMPERATURE =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:℃|°C|度)");

    private final ProblemCatalogService problemCatalog;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProblemUnderstandingService(
            ProblemCatalogService problemCatalog,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.problemCatalog = problemCatalog;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ProblemUnderstanding understand(ProblemUnderstandingRequest request) {
        String text = request.originalText() == null ? "" : request.originalText().strip();
        String language = normalizeLanguage(request.language());
        boolean japanese = "ja-JP".equals(language);

        // 先提取可以确定识别的实体，再用实体与原始文本共同匹配问题类型。
        String model = extractModel(text).orElse("");
        String errorCode = extract(ERROR_CODE, text).orElse("");
        Optional<ProblemMatch> match = problemCatalog.match(model, errorCode, text);

        // 字段级别直接驱动前端交互：A 阻断、B 强提醒但可继续、C 弱提醒。
        List<UnderstoodField> fields = new ArrayList<>();
        fields.add(field(
                "equipmentModel",
                japanese ? "機器型式" : "设备型号",
                model,
                "A",
                model.isBlank() ? "MISSING" : "EXTRACTED",
                model.isBlank() ? 0 : 0.99,
                japanese
                        ? "機器型式を入力してください（例：RIR1-SSB）。"
                        : "请补充设备型号，例如 RIR1-SSB。"));
        fields.add(field(
                "mainSymptom",
                japanese ? "主な症状" : "主要症状",
                text,
                "A",
                text.isBlank() ? "MISSING" : "EXTRACTED",
                text.isBlank() ? 0 : 0.92,
                japanese
                        ? "現在もっとも目立つ異常症状を入力してください。"
                        : "请描述设备当前最明显的异常现象。"));
        fields.add(field(
                "errorCode",
                japanese ? "エラーコード" : "错误码",
                errorCode,
                "B",
                errorCode.isBlank() ? "MISSING" : "EXTRACTED",
                errorCode.isBlank() ? 0 : 0.99,
                japanese
                        ? "操作パネルにエラーコードが表示されている場合は入力してください。"
                        : "如面板显示错误码，请补充错误码。"));

        String operatingState = extractOperatingState(text, japanese).orElse("");
        fields.add(field(
                "operatingState",
                japanese ? "現在の運転状態" : "当前运行状态",
                operatingState,
                "B",
                operatingState.isBlank() ? "MISSING" : "EXTRACTED",
                operatingState.isBlank() ? 0 : 0.82,
                japanese
                        ? "現在も運転中、停止中、または間欠運転のどれですか。"
                        : "设备当前仍在运行、已停机，还是间歇运行？"));

        String occurrence = extractOccurrence(text, japanese).orElse("");
        fields.add(field(
                "occurrence",
                japanese ? "発生時期・頻度" : "发生时间 / 频率",
                occurrence,
                "B",
                occurrence.isBlank() ? "MISSING" : "EXTRACTED",
                occurrence.isBlank() ? 0 : 0.78,
                japanese
                        ? "異常はいつから発生し、継続的ですか、それとも断続的ですか。"
                        : "异常从何时开始，是持续发生还是偶发？"));

        String measurement = extractMeasurement(text).orElse("");
        fields.add(field(
                "measurement",
                japanese ? "現場測定値" : "现场测量值",
                measurement,
                "B",
                measurement.isBlank() ? "MISSING" : "EXTRACTED",
                measurement.isBlank() ? 0 : 0.9,
                japanese
                        ? "温度、電圧、圧力、抵抗値を測定済みの場合は入力してください。"
                        : "如已测量温度、电压、压力或阻值，请补充数值。"));
        fields.add(field(
                "environment",
                japanese ? "設置環境" : "安装环境",
                "",
                "C",
                "MISSING",
                0,
                japanese
                        ? "周囲温度、換気状態、扉の開閉頻度などを追加入力できます。"
                        : "可补充环境温度、通风、门体使用频率等信息。"));
        Optional<String> recentChange = extractRecentChange(text, japanese);
        fields.add(field(
                "recentChanges",
                japanese ? "最近の変更" : "近期变化",
                recentChange.orElse(""),
                "C",
                recentChange.isPresent() ? "EXTRACTED" : "MISSING",
                recentChange.isPresent() ? 0.72 : 0,
                japanese
                        ? "最近の清掃、移設、修理、設定変更があれば入力してください。"
                        : "可补充近期清洁、搬动、维修或设定变更。"));
        fields.add(field(
                "photoEvidence",
                japanese ? "現場写真" : "现场照片",
                "",
                "C",
                "MISSING",
                0,
                japanese
                        ? "現場写真は着霜、目詰まり、漏水などの確認に役立ちます。"
                        : "现场照片可帮助确认结霜、脏堵、漏水等视觉线索。"));

        // 当前 V1 的可靠诊断最小条件是“明确型号 + 非空症状描述”。
        // 错误码并非所有故障都有，因此它属于 B 类而不是硬门槛。
        boolean ready = !model.isBlank() && !text.isBlank();
        String blocking = ready
                ? null
                : japanese
                        ? "必須情報（A）が不足しているため、信頼できる診断を開始できません。"
                        : "缺少 A 类必要信息，暂时不能进行可靠诊断。";
        ProblemType primary = match
                .map(value -> new ProblemType(
                        value.definition().code(),
                        japanese
                                ? value.definition().nameJa()
                                : value.definition().nameZh(),
                        value.score()))
                .orElse(new ProblemType(
                        "UNCLASSIFIED",
                        japanese ? "問題カテゴリ未分類" : "尚未识别问题类别",
                        0));
        String summary = match
                .map(value -> japanese
                        ? "%s、初期分類は「%s」です。以降は同一型式の解決済み事例を優先検索します。"
                                .formatted(
                                        model.isBlank() ? "機器型式未入力" : model,
                                        value.definition().nameJa())
                        : "%s，初步归类为“%s”，后续将优先检索同型号已解决案例。"
                                .formatted(
                                        model.isBlank() ? "设备型号待补充" : model,
                                        value.definition().nameZh()))
                .orElse(japanese
                        ? "入力内容は保存しましたが、現時点の情報では登録済みの保守問題カテゴリに分類できません。"
                        : "已保留原始问题，但当前信号不足以匹配已定义的维保问题类型。");

        ProblemUnderstanding understanding = new ProblemUnderstanding(
                UUID.randomUUID().toString(),
                text,
                language,
                summary,
                primary,
                List.copyOf(fields),
                ready,
                blocking);
        // 保存完整快照而不是只保存拆分字段，保证后续诊断使用的是用户当时确认的版本。
        jdbcTemplate.update("""
                INSERT INTO problem_understanding_snapshot_v1 (
                    understanding_key, stage, language_code,
                    original_text, primary_problem_type_code,
                    ready_for_analysis, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """,
                understanding.id(),
                normalizeStage(request.stage()),
                language,
                text,
                primary.code(),
                ready,
                objectMapper.writeValueAsString(understanding));
        return understanding;
    }

    public ProblemUnderstanding get(String id) {
        // JSON 快照是 V1 的读模型；未来字段拆表后仍可继续保留它作为审计副本。
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload_json
                FROM problem_understanding_snapshot_v1
                WHERE understanding_key = ?
                """, String.class, id);
        return objectMapper.readValue(payload, ProblemUnderstanding.class);
    }

    private Optional<String> extractModel(String text) {
        // 从 taxonomy 已登记型号中匹配，并优先较长型号，避免短型号误命中长型号片段。
        return problemCatalog.all().stream()
                .flatMap(definition -> definition.modelScopes().stream())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(model -> text.toUpperCase(Locale.ROOT)
                        .contains(model.toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    private Optional<String> extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find()
                ? Optional.of(matcher.group().toUpperCase(Locale.ROOT))
                : Optional.empty();
    }

    private Optional<String> extractOperatingState(String text, boolean japanese) {
        // “反复启停 / 起動と停止を繰り返す”包含停止字样，因此先匹配更具体的间歇状态，
        // 再处理单纯停机，避免日文自然描述被宽泛的“停止”条件提前截获。
        if (containsAny(text, "间歇", "偶尔启动", "反复启停",
                "間欠", "断続", "起動と停止を繰り返", "起動・停止を繰り返")) {
            return Optional.of(japanese ? "間欠運転" : "间歇运行");
        }
        if (containsAny(text, "跳停", "停机", "不启动", "无法启动", "停止",
                "停止中", "起動しない", "運転停止", "すぐ止まる")) {
            return Optional.of(japanese ? "停止中または連続運転不可" : "已停机或无法持续运行");
        }
        if (containsAny(text, "仍在运行", "持续运行", "不停机",
                "運転中", "運転を継続", "運転は継続", "継続運転", "連続運転")) {
            return Optional.of(japanese ? "運転継続中" : "仍在运行");
        }
        return Optional.empty();
    }

    private Optional<String> extractOccurrence(String text, boolean japanese) {
        List<String> markers = List.of(
                "最近",
                "持续",
                "昨天",
                "今天",
                "刚刚",
                "反复",
                "偶发",
                "高峰期",
                "每次",
                "数日前",
                "本日",
                "昨日",
                "継続",
                "断続的",
                "繰り返し",
                "繁忙時間帯");
        return markers.stream()
                .filter(text::contains)
                .findFirst()
                .map(value -> japanese
                        ? "発生時期・頻度の記述あり：" + value
                        : "包含时间/频率线索：" + value);
    }

    private Optional<String> extractMeasurement(String text) {
        Matcher matcher = TEMPERATURE.matcher(text);
        return matcher.find()
                ? Optional.of(matcher.group())
                : Optional.empty();
    }

    private Optional<String> extractRecentChange(String text, boolean japanese) {
        return List.of(
                        "清洁", "维修", "搬动", "更换", "设定", "点检",
                        "清掃", "修理", "移設", "交換", "設定", "点検")
                .stream()
                .filter(text::contains)
                .findFirst()
                .map(value -> japanese ? "最近の" + value + "について記載あり" : "已提及近期" + value);
    }

    private UnderstoodField field(
            String code,
            String label,
            String value,
            String level,
            String state,
            double confidence,
            String prompt) {
        return new UnderstoodField(
                code,
                label,
                value.isBlank() ? null : value,
                null,
                value.isBlank() ? null : value,
                level,
                state,
                confidence,
                prompt);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeStage(String stage) {
        // 对外只允许两个受控阶段，未知值安全回退到出发前分析。
        return "ONSITE".equals(stage) ? "ONSITE" : "PRE_DEPARTURE";
    }

    private String normalizeLanguage(String language) {
        return "ja-JP".equals(language) ? "ja-JP" : "zh-CN";
    }
}
