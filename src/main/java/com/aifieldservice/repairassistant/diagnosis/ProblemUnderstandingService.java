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
        String model = extractModel(text).orElse("");
        String errorCode = extract(ERROR_CODE, text).orElse("");
        Optional<ProblemMatch> match = problemCatalog.match(model, errorCode, text);

        List<UnderstoodField> fields = new ArrayList<>();
        fields.add(field(
                "equipmentModel",
                "设备型号",
                model,
                "A",
                model.isBlank() ? "MISSING" : "EXTRACTED",
                model.isBlank() ? 0 : 0.99,
                "请补充设备型号，例如 RIR1-SSB。"));
        fields.add(field(
                "mainSymptom",
                "主要症状",
                text,
                "A",
                text.isBlank() ? "MISSING" : "EXTRACTED",
                text.isBlank() ? 0 : 0.92,
                "请描述设备当前最明显的异常现象。"));
        fields.add(field(
                "errorCode",
                "错误码",
                errorCode,
                "B",
                errorCode.isBlank() ? "MISSING" : "EXTRACTED",
                errorCode.isBlank() ? 0 : 0.99,
                "如面板显示错误码，请补充错误码。"));

        String operatingState = extractOperatingState(text).orElse("");
        fields.add(field(
                "operatingState",
                "当前运行状态",
                operatingState,
                "B",
                operatingState.isBlank() ? "MISSING" : "EXTRACTED",
                operatingState.isBlank() ? 0 : 0.82,
                "设备当前仍在运行、已停机，还是间歇运行？"));

        String occurrence = extractOccurrence(text).orElse("");
        fields.add(field(
                "occurrence",
                "发生时间 / 频率",
                occurrence,
                "B",
                occurrence.isBlank() ? "MISSING" : "EXTRACTED",
                occurrence.isBlank() ? 0 : 0.78,
                "异常从何时开始，是持续发生还是偶发？"));

        String measurement = extractMeasurement(text).orElse("");
        fields.add(field(
                "measurement",
                "现场测量值",
                measurement,
                "B",
                measurement.isBlank() ? "MISSING" : "EXTRACTED",
                measurement.isBlank() ? 0 : 0.9,
                "如已测量温度、电压、压力或阻值，请补充数值。"));
        fields.add(field(
                "environment",
                "安装环境",
                "",
                "C",
                "MISSING",
                0,
                "可补充环境温度、通风、门体使用频率等信息。"));
        fields.add(field(
                "recentChanges",
                "近期变化",
                extractRecentChange(text).orElse(""),
                "C",
                extractRecentChange(text).isPresent() ? "EXTRACTED" : "MISSING",
                extractRecentChange(text).isPresent() ? 0.72 : 0,
                "可补充近期清洁、搬动、维修或设定变更。"));
        fields.add(field(
                "photoEvidence",
                "现场照片",
                "",
                "C",
                "MISSING",
                0,
                "现场照片可帮助确认结霜、脏堵、漏水等视觉线索。"));

        boolean ready = !model.isBlank() && !text.isBlank();
        String blocking = ready ? null : "缺少 A 类必要信息，暂时不能进行可靠诊断。";
        ProblemType primary = match
                .map(value -> new ProblemType(
                        value.definition().code(),
                        value.definition().nameZh(),
                        value.score()))
                .orElse(new ProblemType(
                        "UNCLASSIFIED",
                        "尚未识别问题类别",
                        0));
        String summary = match
                .map(value -> "%s，初步归类为“%s”，后续将优先检索同型号已解决案例。"
                        .formatted(
                                model.isBlank() ? "设备型号待补充" : model,
                                value.definition().nameZh()))
                .orElse("已保留原始问题，但当前信号不足以匹配已定义的维保问题类型。");

        ProblemUnderstanding understanding = new ProblemUnderstanding(
                UUID.randomUUID().toString(),
                text,
                summary,
                primary,
                List.copyOf(fields),
                ready,
                blocking);
        jdbcTemplate.update("""
                INSERT INTO problem_understanding_snapshot_v1 (
                    understanding_key, stage, language_code,
                    original_text, primary_problem_type_code,
                    ready_for_analysis, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """,
                understanding.id(),
                normalizeStage(request.stage()),
                normalizeLanguage(request.language()),
                text,
                primary.code(),
                ready,
                objectMapper.writeValueAsString(understanding));
        return understanding;
    }

    public ProblemUnderstanding get(String id) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload_json
                FROM problem_understanding_snapshot_v1
                WHERE understanding_key = ?
                """, String.class, id);
        return objectMapper.readValue(payload, ProblemUnderstanding.class);
    }

    private Optional<String> extractModel(String text) {
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

    private Optional<String> extractOperatingState(String text) {
        if (containsAny(text, "跳停", "停机", "不启动", "无法启动", "停止")) {
            return Optional.of("已停机或无法持续运行");
        }
        if (containsAny(text, "仍在运行", "持续运行", "不停机")) {
            return Optional.of("仍在运行");
        }
        if (containsAny(text, "间歇", "偶尔启动", "反复启停")) {
            return Optional.of("间歇运行");
        }
        return Optional.empty();
    }

    private Optional<String> extractOccurrence(String text) {
        List<String> markers = List.of(
                "最近",
                "持续",
                "昨天",
                "今天",
                "刚刚",
                "反复",
                "偶发",
                "高峰期",
                "每次");
        return markers.stream()
                .filter(text::contains)
                .findFirst()
                .map(value -> "包含时间/频率线索：" + value);
    }

    private Optional<String> extractMeasurement(String text) {
        Matcher matcher = TEMPERATURE.matcher(text);
        return matcher.find()
                ? Optional.of(matcher.group())
                : Optional.empty();
    }

    private Optional<String> extractRecentChange(String text) {
        return List.of("清洁", "维修", "搬动", "更换", "设定", "点检")
                .stream()
                .filter(text::contains)
                .findFirst()
                .map(value -> "已提及近期" + value);
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
        return "ONSITE".equals(stage) ? "ONSITE" : "PRE_DEPARTURE";
    }

    private String normalizeLanguage(String language) {
        return "ja-JP".equals(language) ? "ja-JP" : "zh-CN";
    }
}
