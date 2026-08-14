package com.aifieldservice.repairassistant.service.onsite;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.dao.diagnosis.CauseHypothesisMapper;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Generates one controlled onsite question from the registered hypothesis templates. */
@Component
public class OnsiteQuestionFactory {

    private static final TypeReference<List<Map<String, Object>>> QUESTION_TYPE = new TypeReference<>() { };
    private static final Map<String, List<QuestionOption>> OPTIONS = Map.of(
            "condenserState", List.of(new QuestionOption("BLOCKED", "明显堵塞"), new QuestionOption("DUSTY", "轻微积尘"), new QuestionOption("CLEAN", "清洁")),
            "doorSealState", List.of(new QuestionOption("DAMAGED", "破损或漏气"), new QuestionOption("NORMAL", "状态正常")),
            "fanState", List.of(new QuestionOption("STOPPED", "停止"), new QuestionOption("INTERMITTENT", "间歇运行"), new QuestionOption("NORMAL", "运行正常")),
            "oilTrace", List.of(new QuestionOption("PRESENT", "发现油迹"), new QuestionOption("ABSENT", "未发现油迹")),
            "compressorContinuousRun", List.of(new QuestionOption("YES", "持续运行"), new QuestionOption("NO", "会正常停机")),
            "compressorState", List.of(new QuestionOption("RUNNING", "正在运行"), new QuestionOption("NOT_RUNNING", "未运行")),
            "frostState", List.of(new QuestionOption("HEAVY", "严重结霜"), new QuestionOption("LIGHT", "轻微结霜"), new QuestionOption("NORMAL", "无异常结霜")),
            "pressure", List.of(new QuestionOption("ABNORMAL", "压力异常"), new QuestionOption("NORMAL", "状态正常")),
            "highPressureSwitchState", List.of(new QuestionOption("TRIPPED", "已动作"), new QuestionOption("NORMAL", "状态正常")));
    private static final Map<String, String> JAPANESE_PROMPTS = Map.ofEntries(
            Map.entry("condenserState", "凝縮器とフィルタに汚れや目詰まりがありますか。"),
            Map.entry("airflowState", "機器周辺の通風は確保されていますか。"),
            Map.entry("pressure", "清掃と通風確認後の高圧側・低圧側圧力はいくつですか。"),
            Map.entry("recentRefrigerantService", "最近、冷媒回路の修理または充填を行いましたか。"),
            Map.entry("pressureSwitchState", "実測圧力と高圧スイッチ出力は一致していますか。"),
            Map.entry("highPressureSwitchState", "高圧スイッチは作動していますか。"),
            Map.entry("fanState", "ファンは正常に運転していますか。"),
            Map.entry("doorSealState", "ドアパッキンに破損や空気漏れがありますか。"),
            Map.entry("setTemperature", "設定温度はいくつですか。"),
            Map.entry("measuredTemperature", "独立温度計による実測温度はいくつですか。"),
            Map.entry("displayTemperature", "操作パネルの表示温度はいくつですか。"));

    private final CauseHypothesisMapper hypothesisMapper;
    private final ObjectMapper objectMapper;

    public OnsiteQuestionFactory(CauseHypothesisMapper hypothesisMapper, ObjectMapper objectMapper) {
        this.hypothesisMapper = hypothesisMapper;
        this.objectMapper = objectMapper;
    }

    public OnsiteQuestion create(List<DiagnosisCandidate> candidates, Set<String> answeredFields, int round, boolean japanese) {
        for (DiagnosisCandidate candidate : candidates) {
            String questionsJson = hypothesisMapper.findClarificationQuestionsJson(candidate.code());
            if (questionsJson == null) continue;
            try {
                for (Map<String, Object> template : objectMapper.readValue(questionsJson, QUESTION_TYPE)) {
                    String field = String.valueOf(template.getOrDefault("field", "")).strip();
                    String prompt = japanese ? JAPANESE_PROMPTS.getOrDefault(field, "現場で「%s」を確認してください。".formatted(field)) : String.valueOf(template.getOrDefault("questionZh", "")).strip();
                    if (field.isBlank() || prompt.isBlank() || answeredFields.contains(field)) continue;
                    List<QuestionOption> options = OPTIONS.get(field);
                    String type;
                    String unit = null;
                    if (options != null) { type = "SINGLE_CHOICE"; }
                    else if (measurement(field)) { type = "MEASUREMENT"; unit = unit(field); options = List.of(); }
                    else { type = "SINGLE_CHOICE"; options = List.of(new QuestionOption("ABNORMAL", japanese ? "異常あり" : "存在异常"), new QuestionOption("NORMAL", japanese ? "正常" : "状态正常")); }
                    return new OnsiteQuestion(UUID.randomUUID().toString(), type, prompt, field, candidate.code(), round, unit, japanese && options != null ? japaneseOptions(options) : options);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private boolean measurement(String field) { String f = field.toLowerCase(Locale.ROOT); return f.contains("temperature") || f.contains("resistance") || f.contains("voltage") || f.contains("current"); }
    private String unit(String field) { String f = field.toLowerCase(Locale.ROOT); return f.contains("temperature") ? "°C" : f.contains("resistance") ? "Ω" : f.contains("voltage") ? "V" : f.contains("current") ? "A" : null; }
    private List<QuestionOption> japaneseOptions(List<QuestionOption> options) { return options.stream().map(o -> new QuestionOption(o.code(), switch (o.code()) { case "BLOCKED" -> "明らかな目詰まり"; case "DUSTY" -> "軽いほこり付着"; case "CLEAN" -> "清潔"; case "DAMAGED" -> "破損または空気漏れ"; case "STOPPED" -> "停止"; case "INTERMITTENT" -> "間欠運転"; case "PRESENT" -> "油跡あり"; case "ABSENT" -> "油跡なし"; case "YES" -> "はい"; case "NO" -> "いいえ"; case "RUNNING" -> "運転中"; case "NOT_RUNNING" -> "停止中"; case "HEAVY" -> "著しい着霜"; case "LIGHT" -> "軽い着霜"; case "ABNORMAL" -> "異常"; case "TRIPPED" -> "作動済み"; case "NORMAL" -> "正常"; default -> o.label(); })).toList(); }
}
