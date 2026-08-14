package com.aifieldservice.repairassistant.service.diagnosis.impl;

import com.aifieldservice.repairassistant.service.diagnosis.*;
import com.aifieldservice.repairassistant.service.diagnosis.ProblemUnderstandingService.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifieldservice.repairassistant.domain.diagnosis.command.ProblemUnderstandingRequest;
import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.dao.diagnosis.ProblemUnderstandingMapper;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemMatch;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemTypeDefinition;
import com.aifieldservice.repairassistant.service.diagnosis.semantic.RuleSufficiencyResult;
import com.aifieldservice.repairassistant.service.diagnosis.semantic.SemanticFallbackPolicy;
import com.aifieldservice.repairassistant.service.diagnosis.semantic.SemanticProblemUnderstandingService;
import com.aifieldservice.repairassistant.integration.openai.SemanticProblemUnderstandingResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * 把用户自然语言转换成标准化的维修问题模型。
 *
 * <p>V1 使用“规则抽取 + 受控问题分类”，而不是让 LLM 自由生成字段：
 * 型号来自已登记问题类型的 model scope，错误码和测量值由正则提取，
 * 问题类型由 {@link ProblemCatalogService} 评分。这样关键字段稳定、可解释、可测试。
 */
@Service
public class ProblemUnderstandingServiceImpl implements ProblemUnderstandingService {

    private static final Pattern ERROR_CODE =
            Pattern.compile("(?i)(?<![A-Z0-9])E\\d+(?![A-Z0-9])");
    private static final Pattern TEMPERATURE =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:℃|°C|度)");

    private final ProblemCatalogService problemCatalog;
    private final ProblemUnderstandingMapper problemUnderstandingMapper;
    private final ObjectMapper objectMapper;
    private final SemanticFallbackPolicy semanticFallbackPolicy;
    private final SemanticProblemUnderstandingService semanticProblemUnderstandingService;

    public ProblemUnderstandingServiceImpl(
            ProblemCatalogService problemCatalog,
            ProblemUnderstandingMapper problemUnderstandingMapper,
            ObjectMapper objectMapper,
            SemanticFallbackPolicy semanticFallbackPolicy,
            SemanticProblemUnderstandingService semanticProblemUnderstandingService) {
        this.problemCatalog = problemCatalog;
        this.problemUnderstandingMapper = problemUnderstandingMapper;
        this.objectMapper = objectMapper;
        this.semanticFallbackPolicy = semanticFallbackPolicy;
        this.semanticProblemUnderstandingService = semanticProblemUnderstandingService;
    }

    @Transactional
    public ProblemUnderstanding understand(ProblemUnderstandingRequest request) {
        String text = request.originalText() == null ? "" : request.originalText().strip();
        String language = normalizeLanguage(request.language());
        boolean japanese = "ja-JP".equals(language);

        // 先提取可以确定识别的实体，再用实体与原始文本共同匹配问题类型。
        String model = extractModel(text).orElse("");
        String errorCode = extract(ERROR_CODE, text).orElse("");
        boolean errorCodeExplicitlyAbsent = errorCode.isBlank() && containsAny(text,
                "未显示错误码", "没有错误码", "无错误码", "未报故障码",
                "エラーコードは表示されていない", "エラーコードなし", "エラー表示なし");
        List<ProblemMatch> candidates = problemCatalog.matchCandidates(model, errorCode, text);
        Optional<ProblemMatch> match = candidates.stream().findFirst();
        boolean meaningfulSymptom = hasMeaningfulSymptom(text);

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
                meaningfulSymptom ? "EXTRACTED" : "MISSING",
                meaningfulSymptom ? 0.92 : 0,
                japanese
                        ? "現在もっとも目立つ異常症状を入力してください。"
                        : "请描述设备当前最明显的异常现象。"));
        fields.add(field(
                "errorCode",
                japanese ? "エラーコード" : "错误码",
                errorCodeExplicitlyAbsent ? (japanese ? "表示なし" : "未显示") : errorCode,
                "B",
                errorCodeExplicitlyAbsent ? "CONFIRMED_ABSENT" : errorCode.isBlank() ? "MISSING" : "EXTRACTED",
                errorCodeExplicitlyAbsent ? 0.95 : errorCode.isBlank() ? 0 : 0.99,
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

        RuleSufficiencyResult sufficiency = semanticFallbackPolicy.evaluate(
                candidates, !model.isBlank(), meaningfulSymptom);

        // 规则达到可分析阈值后绝不额外调用模型补字段，避免确定性请求承担网络延迟。
        // LLM 仅用于“没有达到规则阈值”的受控分类兜底。
        Optional<SemanticProblemUnderstandingResponse> semantic = sufficiency.shouldInvokeLlmForClassification()
                && semanticProblemUnderstandingService.enabled()
                ? semanticProblemUnderstandingService.understand(text, language, problemCatalog.all())
                : Optional.empty();
        Optional<ProblemTypeDefinition> semanticType = semantic
                .filter(value -> !sufficiency.classificationSufficient())
                .filter(value -> !"UNCLASSIFIED".equals(value.problemTypeCode()))
                .flatMap(value -> problemCatalog.findByCode(value.problemTypeCode()));
        if (semantic.isPresent()) {
            // 模型只能补充规则遗漏字段；允许基于整段语义归纳，不要求逐字证据匹配。
            fields = mergeSemanticFields(fields, semantic.get(), japanese);
        }

        boolean ready = sufficiency.analysisReady() || semanticType.isPresent();
        String blocking = ready ? null : blockingMessage(japanese, model, meaningfulSymptom, sufficiency);
        // 低于“充分”门槛的规则候选仅用于决定是否兜底，不作为对外最终分类。
        Optional<ProblemMatch> acceptedRuleMatch = sufficiency.classificationSufficient()
                ? match : Optional.empty();
        ProblemType primary = semanticType
                .map(value -> new ProblemType(
                        value.code(), japanese ? value.nameJa() : value.nameZh(),
                        semantic.orElseThrow().classificationConfidence() * 100))
                .or(() -> acceptedRuleMatch.map(value -> new ProblemType(
                        value.definition().code(),
                        japanese
                                ? value.definition().nameJa()
                                : value.definition().nameZh(),
                        value.score())))
                .orElse(new ProblemType(
                        "UNCLASSIFIED",
                        japanese ? "問題カテゴリ未分類" : "尚未识别问题类别",
                        0));
        String summary = semanticType
                .map(value -> japanese
                        ? "%s、意味解析により初期分類は「%s」です。確認済み taxonomy の範囲で検索します。"
                                .formatted(model, value.nameJa())
                        : "%s，经受控语义理解初步归类为“%s”，后续仅在已登记分类范围内检索。"
                                .formatted(model, value.nameZh()))
                .or(() -> acceptedRuleMatch
                .map(value -> japanese
                        ? "%s、初期分類は「%s」です。以降は同一型式の解決済み事例を優先検索します。"
                                .formatted(
                                        model.isBlank() ? "機器型式未入力" : model,
                                        value.definition().nameJa())
                        : "%s，初步归类为“%s”，后续将优先检索同型号已解决案例。"
                                .formatted(
                                        model.isBlank() ? "设备型号待补充" : model,
                                        value.definition().nameZh())))
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
        problemUnderstandingMapper.insert(
                understanding.id(),
                normalizeStage(request.stage()),
                language,
                text,
                primary.code(),
                ready,
                objectMapper.writeValueAsString(understanding));
        return understanding;
    }

    @Transactional(readOnly = true)
    public ProblemUnderstanding get(String id) {
        // JSON 快照是 V1 的读模型；未来字段拆表后仍可继续保留它作为审计副本。
        String payload = problemUnderstandingMapper.findPayloadByUnderstandingKey(id);
        return objectMapper.readValue(payload, ProblemUnderstanding.class);
    }

    private List<UnderstoodField> mergeSemanticFields(
            List<UnderstoodField> fields,
            SemanticProblemUnderstandingResponse semantic,
            boolean japanese) {
        return fields.stream().map(existing -> {
            var semanticField = semantic.fields().get(existing.code());
            if (!"MISSING".equals(existing.state()) || semanticField == null) {
                return existing;
            }
            String state = switch (semanticField.status()) {
                case "ABSENT" -> "CONFIRMED_ABSENT";
                default -> "EXTRACTED";
            };
            return new UnderstoodField(
                    existing.code(), existing.label(), semanticField.value(), existing.unit(),
                    semanticField.evidence(), existing.level(), state, semanticField.confidence(),
                    existing.prompt());
        }).toList();
    }

    private String blockingMessage(
            boolean japanese,
            String model,
            boolean meaningfulSymptom,
            RuleSufficiencyResult sufficiency) {
        if (model.isBlank() || !meaningfulSymptom) {
            return japanese
                    ? "必須情報（A）が不足しています。機器型式と現在もっとも目立つ異常症状を補足してください。"
                    : "缺少 A 类必要信息，请补充设备型号和当前最明显的异常现象。";
        }
        // 保留未分类而非猜测分类；没有 Key 与模型失败都走同一条安全回退。
        return japanese
                ? "問題分類の根拠が不足しています。エラーコード、運転状態または具体的な症状を補足してください。"
                : "问题分类依据不足，请补充错误码、运行状态或更具体的异常现象。";
    }

    private boolean hasMeaningfulSymptom(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        // 仅有求助性短句并不代表用户提供了可用于诊断的故障事实。
        return !List.of("设备有问题", "设备异常", "帮我看看", "请处理", "故障了", "机器有问题")
                .contains(normalized);
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
        if (containsAny(text, "当前冷却正常", "制冷运行正常", "运行没有问题",
                "現在冷却操作も問題はない", "冷却運転に問題はない", "運転に問題なし")) {
            return Optional.of(japanese ? "冷却運転は正常" : "当前冷却运行正常");
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
        if (containsAny(text, "前回は正常", "上次正常", "此前正常")
                && containsAny(text, "今回は", "本次", "这次")) {
            return Optional.of(japanese ? "前回正常・今回発生" : "此前正常、本次发生");
        }
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
