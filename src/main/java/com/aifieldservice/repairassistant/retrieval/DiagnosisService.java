package com.aifieldservice.repairassistant.retrieval;

import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.AnalysisProgress;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.DiagnosisCandidate;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.DiagnosisSession;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.EvidenceGroup;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.EvidenceItem;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.OnsiteQuestion;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.OnsiteQuestionResponseRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.PartRecommendation;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstanding;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.QuestionOption;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.Recommendations;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.RepairStep;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.SaveReportRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.SavedReport;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.StartDiagnosisRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ToolRecommendation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.integration.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.QdrantGateway;
import com.aifieldservice.repairassistant.knowledge.ProblemCatalogService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiagnosisService {

    private static final TypeReference<List<Map<String, Object>>> PARTS_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<Map<String, Object>>> QUESTION_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<AnsweredSignal>> SIGNALS_TYPE =
            new TypeReference<>() {
            };

    private static final Map<String, List<QuestionOption>> QUESTION_OPTIONS =
            Map.ofEntries(
                    Map.entry("condenserState", List.of(
                            new QuestionOption("BLOCKED", "明显堵塞"),
                            new QuestionOption("DUSTY", "轻微积尘"),
                            new QuestionOption("CLEAN", "清洁"))),
                    Map.entry("doorSealState", List.of(
                            new QuestionOption("DAMAGED", "破损或漏气"),
                            new QuestionOption("NORMAL", "状态正常"))),
                    Map.entry("fanState", List.of(
                            new QuestionOption("STOPPED", "停止"),
                            new QuestionOption("INTERMITTENT", "间歇运行"),
                            new QuestionOption("NORMAL", "运行正常"))),
                    Map.entry("oilTrace", List.of(
                            new QuestionOption("PRESENT", "发现油迹"),
                            new QuestionOption("ABSENT", "未发现油迹"))),
                    Map.entry("compressorContinuousRun", List.of(
                            new QuestionOption("YES", "持续运行"),
                            new QuestionOption("NO", "会正常停机"))),
                    Map.entry("compressorState", List.of(
                            new QuestionOption("RUNNING", "正在运行"),
                            new QuestionOption("NOT_RUNNING", "未运行"))),
                    Map.entry("frostState", List.of(
                            new QuestionOption("HEAVY", "严重结霜"),
                            new QuestionOption("LIGHT", "轻微结霜"),
                            new QuestionOption("NORMAL", "无异常结霜"))),
                    Map.entry("pressure", List.of(
                            new QuestionOption("ABNORMAL", "压力异常"),
                            new QuestionOption("NORMAL", "压力正常"))),
                    Map.entry("highPressureSwitchState", List.of(
                            new QuestionOption("TRIPPED", "已动作"),
                            new QuestionOption("NORMAL", "状态正常"))));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProblemCatalogService problemCatalog;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;

    public DiagnosisService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ProblemCatalogService problemCatalog,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.problemCatalog = problemCatalog;
        this.openAiGateway = openAiGateway;
        this.qdrantGateway = qdrantGateway;
    }

    public DiagnosisSession start(StartDiagnosisRequest request) {
        ProblemUnderstanding understanding = loadUnderstanding(
                request.problemUnderstandingId());
        if (!understanding.readyForAnalysis()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    understanding.blockingMessage());
        }

        String model = stringField(understanding, "equipmentModel");
        String problemTypeCode = understanding.primaryProblemType().code();
        List<RetrievedCase> cases = retrieveStructuredCases(model, problemTypeCode);

        if (cases.size() < 3 && openAiGateway.enabled()) {
            List<float[]> embeddings = openAiGateway.embed(
                    List.of(understanding.originalText()));
            if (!embeddings.isEmpty()) {
                List<String> semanticReceptionIds = qdrantGateway
                        .search(embeddings.get(0), model, problemTypeCode, 8, 0.58)
                        .stream()
                        .map(QdrantGateway.SearchHit::receptionId)
                        .toList();
                cases = mergeCases(
                        cases,
                        retrieveByReceptionIds(semanticReceptionIds));
            }
        }

        List<EvidenceItem> caseEvidence = buildCaseEvidence(cases);
        List<EvidenceItem> partEvidence = buildPartEvidence(cases);
        List<EvidenceGroup> evidenceGroups = new ArrayList<>();
        if (!caseEvidence.isEmpty()) {
            evidenceGroups.add(new EvidenceGroup(
                    "REPAIR_CASE",
                    "维修历史记录",
                    caseEvidence));
        }
        if (!partEvidence.isEmpty()) {
            evidenceGroups.add(new EvidenceGroup(
                    "PART_REFERENCE",
                    "历史备件记录",
                    partEvidence));
        }

        List<DiagnosisCandidate> candidates = buildCandidates(
                understanding,
                cases,
                caseEvidence);
        if (!candidates.isEmpty()) {
            List<Map<String, Object>> candidatePrompt = candidates.stream()
                    .map(candidate -> Map.<String, Object>of(
                            "name", candidate.label(),
                            "supportScore", candidate.supportScore(),
                            "explanation", candidate.explanation()))
                    .toList();
            List<Map<String, Object>> evidencePrompt = caseEvidence.stream()
                    .map(evidence -> Map.<String, Object>of(
                            "id", evidence.id(),
                            "summary", evidence.summary(),
                            "trust", evidence.trustLabel()))
                    .toList();
            Optional<String> aiSummary = openAiGateway.explainDiagnosis(
                    understanding.originalText(),
                    understanding.primaryProblemType().label(),
                    candidatePrompt,
                    evidencePrompt);
            if (aiSummary.isPresent()) {
                DiagnosisCandidate first = candidates.get(0);
                candidates = new ArrayList<>(candidates);
                candidates.set(0, new DiagnosisCandidate(
                        first.code(),
                        first.label(),
                        first.rank(),
                        first.supportScore(),
                        first.supportBand(),
                        aiSummary.get(),
                        first.evidenceIds()));
            }
        }

        Recommendations recommendations = new Recommendations(
                buildPartRecommendations(cases, partEvidence),
                buildToolRecommendations(problemTypeCode),
                buildRepairSteps(cases, caseEvidence));
        String status = candidates.isEmpty()
                ? "INSUFFICIENT_EVIDENCE"
                : caseEvidence.size() >= 2
                        ? "READY"
                        : "PARTIALLY_SUPPORTED";
        DiagnosisSession session = new DiagnosisSession(
                UUID.randomUUID().toString(),
                "PRE_DEPARTURE",
                status,
                new AnalysisProgress("GENERATING_EXPLANATION", 100),
                understanding,
                candidates,
                List.copyOf(evidenceGroups),
                recommendations,
                null,
                Instant.now());
        insertDiagnosisSnapshot(session);
        return session;
    }

    public DiagnosisSession get(String sessionId) {
        List<String> rows = jdbcTemplate.query("""
                SELECT payload_json
                FROM diagnosis_snapshot_v1
                WHERE session_key = ?
                """, (resultSet, rowNum) -> resultSet.getString(1), sessionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "诊断会话不存在。");
        }
        return objectMapper.readValue(rows.get(0), DiagnosisSession.class);
    }

    public DiagnosisSession enterOnsite(String parentSessionId) {
        DiagnosisSession parent = get(parentSessionId);
        if ("ONSITE".equals(parent.stage())) {
            return parent;
        }

        List<String> existing = jdbcTemplate.query("""
                SELECT session_key
                FROM onsite_session_state_v1
                WHERE parent_session_key = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (resultSet, rowNum) -> resultSet.getString(1), parentSessionId);
        if (!existing.isEmpty()) {
            return get(existing.get(0));
        }

        String sessionId = UUID.randomUUID().toString();
        OnsiteQuestion question = parent.candidates().isEmpty()
                ? null
                : nextQuestion(parent.candidates(), Set.of(), 1);
        String status = parent.candidates().isEmpty()
                ? "INSUFFICIENT_EVIDENCE"
                : question == null ? "PARTIALLY_SUPPORTED" : "ONSITE_QUESTIONING";
        DiagnosisSession onsite = new DiagnosisSession(
                sessionId,
                "ONSITE",
                status,
                new AnalysisProgress("ONSITE_QUESTION_GENERATION", 100),
                parent.problemUnderstanding(),
                parent.candidates(),
                parent.evidenceGroups(),
                parent.recommendations(),
                question,
                Instant.now());
        insertDiagnosisSnapshot(onsite);
        jdbcTemplate.update("""
                INSERT INTO onsite_session_state_v1 (
                    session_key, parent_session_key, current_round,
                    max_rounds, answered_signals_json
                ) VALUES (?, ?, 1, 3, CAST(? AS JSON))
                """,
                sessionId,
                parentSessionId,
                "[]");
        return onsite;
    }

    public DiagnosisSession answerOnsiteQuestion(
            String sessionId,
            String questionId,
            OnsiteQuestionResponseRequest request) {
        DiagnosisSession session = get(sessionId);
        if (!"ONSITE".equals(session.stage()) || session.nextQuestion() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前现场会话没有待回答问题。");
        }
        if (!session.nextQuestion().id().equals(questionId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "现场问题已更新，请刷新后重新回答。");
        }

        OnsiteState state = loadOnsiteState(sessionId);
        AnsweredSignal answer = normalizeAnswer(session.nextQuestion(), request);
        List<AnsweredSignal> answers = new ArrayList<>(state.answers());
        answers.add(answer);

        List<DiagnosisCandidate> candidates = rescoreCandidates(
                session.candidates(),
                session.nextQuestion(),
                answer);
        List<EvidenceGroup> evidenceGroups = appendOnsiteEvidence(
                session.evidenceGroups(),
                session.nextQuestion(),
                answer);

        int currentRound = state.currentRound();
        OnsiteQuestion next = null;
        String status;
        if (isConverged(candidates)) {
            status = "CONVERGED";
        } else if (currentRound >= state.maxRounds()) {
            status = hasSupportedCandidate(candidates)
                    ? "PARTIALLY_SUPPORTED"
                    : "INSUFFICIENT_EVIDENCE";
        } else {
            Set<String> answeredFields = answers.stream()
                    .map(AnsweredSignal::field)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            next = nextQuestion(candidates, answeredFields, currentRound + 1);
            status = next == null
                    ? hasSupportedCandidate(candidates)
                            ? "PARTIALLY_SUPPORTED"
                            : "INSUFFICIENT_EVIDENCE"
                    : "ONSITE_QUESTIONING";
        }

        DiagnosisSession updated = new DiagnosisSession(
                session.id(),
                "ONSITE",
                status,
                new AnalysisProgress("ONSITE_REANALYSIS", 100),
                session.problemUnderstanding(),
                candidates,
                evidenceGroups,
                session.recommendations(),
                next,
                Instant.now());
        updateDiagnosisSnapshot(updated);
        jdbcTemplate.update("""
                UPDATE onsite_session_state_v1
                SET current_round = ?,
                    answered_signals_json = CAST(? AS JSON)
                WHERE session_key = ?
                """,
                next == null ? currentRound : next.round(),
                objectMapper.writeValueAsString(answers),
                sessionId);
        return updated;
    }

    public SavedReport saveReport(String sessionId, SaveReportRequest request) {
        DiagnosisSession session = get(sessionId);
        if (session.candidates().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前没有可保存的诊断候选。");
        }
        List<SavedReport> existing = queryReports(
                "WHERE session_key = ?",
                sessionId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        String reportId = UUID.randomUUID().toString();
        String model = stringField(session.problemUnderstanding(), "equipmentModel");
        String defaultName = "%s · %s".formatted(
                model,
                session.problemUnderstanding().primaryProblemType().label());
        String reportName = request.reportName() == null
                || request.reportName().isBlank()
                        ? defaultName
                        : request.reportName().strip();
        jdbcTemplate.update("""
                INSERT INTO saved_diagnosis_report_v1 (
                    report_key, session_key, report_name, note,
                    stage, diagnosis_status, snapshot_json
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """,
                reportId,
                sessionId,
                reportName,
                request.note(),
                session.stage(),
                session.status(),
                objectMapper.writeValueAsString(session));
        return getReport(reportId);
    }

    public List<SavedReport> listReports() {
        return queryReports("ORDER BY created_at DESC");
    }

    public SavedReport getReport(String reportId) {
        List<SavedReport> reports = queryReports(
                "WHERE report_key = ?",
                reportId);
        if (reports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "诊断报告不存在。");
        }
        return reports.get(0);
    }

    private OnsiteQuestion nextQuestion(
            List<DiagnosisCandidate> candidates,
            Set<String> answeredFields,
            int round) {
        for (DiagnosisCandidate candidate : candidates) {
            List<String> rows = jdbcTemplate.query("""
                    SELECT clarification_questions_json
                    FROM cause_hypothesis
                    WHERE code = ?
                    LIMIT 1
                    """,
                    (resultSet, rowNum) -> resultSet.getString(1),
                    candidate.code());
            if (rows.isEmpty() || rows.get(0) == null) {
                continue;
            }

            List<Map<String, Object>> questions;
            try {
                questions = objectMapper.readValue(rows.get(0), QUESTION_TYPE);
            } catch (Exception exception) {
                continue;
            }
            for (Map<String, Object> template : questions) {
                String field = String.valueOf(
                        template.getOrDefault("field", "")).strip();
                String prompt = String.valueOf(
                        template.getOrDefault("questionZh", "")).strip();
                if (field.isBlank()
                        || prompt.isBlank()
                        || answeredFields.contains(field)) {
                    continue;
                }
                List<QuestionOption> options = QUESTION_OPTIONS.get(field);
                String type;
                String unit = null;
                if (options != null) {
                    type = "SINGLE_CHOICE";
                } else if (isMeasurementField(field)) {
                    type = "MEASUREMENT";
                    unit = measurementUnit(field);
                    options = List.of();
                } else {
                    type = "SINGLE_CHOICE";
                    options = List.of(
                            new QuestionOption("ABNORMAL", "存在异常"),
                            new QuestionOption("NORMAL", "状态正常"));
                }
                return new OnsiteQuestion(
                        UUID.randomUUID().toString(),
                        type,
                        prompt,
                        field,
                        candidate.code(),
                        round,
                        unit,
                        options);
            }
        }
        return null;
    }

    private boolean isMeasurementField(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("temperature")
                || normalized.contains("resistance")
                || normalized.contains("voltage")
                || normalized.contains("current");
    }

    private String measurementUnit(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        if (normalized.contains("temperature")) {
            return "°C";
        }
        if (normalized.contains("resistance")) {
            return "Ω";
        }
        if (normalized.contains("voltage")) {
            return "V";
        }
        if (normalized.contains("current")) {
            return "A";
        }
        return null;
    }

    private OnsiteState loadOnsiteState(String sessionId) {
        List<OnsiteState> states = jdbcTemplate.query("""
                SELECT current_round, max_rounds, answered_signals_json
                FROM onsite_session_state_v1
                WHERE session_key = ?
                """, (resultSet, rowNum) -> {
            List<AnsweredSignal> answers;
            try {
                answers = objectMapper.readValue(
                        resultSet.getString("answered_signals_json"),
                        SIGNALS_TYPE);
            } catch (Exception exception) {
                answers = List.of();
            }
            return new OnsiteState(
                    resultSet.getInt("current_round"),
                    resultSet.getInt("max_rounds"),
                    answers);
        }, sessionId);
        if (states.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "现场会话状态不存在，请重新进入现场分析。");
        }
        return states.get(0);
    }

    private AnsweredSignal normalizeAnswer(
            OnsiteQuestion question,
            OnsiteQuestionResponseRequest request) {
        if (request == null || request.responseType() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "请选择或输入现场确认结果。");
        }
        String responseType = request.responseType()
                .strip()
                .toUpperCase(Locale.ROOT);
        String value;
        String label;
        switch (responseType) {
            case "OPTION" -> {
                String selectedCode = safeStrip(request.selectedOptionCode());
                QuestionOption option = question.options().stream()
                        .filter(item -> item.code().equals(selectedCode))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "请选择有效的现场状态。"));
                value = option.code();
                label = option.label();
            }
            case "MEASUREMENT" -> {
                if (request.valueNumber() == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "请输入测量值。");
                }
                String unit = safeStrip(request.unit());
                if (unit.isBlank()) {
                    unit = question.unit() == null ? "" : question.unit();
                }
                value = request.valueNumber() + unit;
                label = "测量值 " + value;
            }
            case "OTHER_TEXT" -> {
                value = safeStrip(request.rawText());
                if (value.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "请输入现场观察内容。");
                }
                label = truncate(value, 80);
            }
            case "UNAVAILABLE" -> {
                value = "UNAVAILABLE";
                label = "现场暂时无法确认";
            }
            case "SKIPPED" -> {
                value = "SKIPPED";
                label = "本轮已跳过";
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "不支持的现场回答类型。");
        }
        return new AnsweredSignal(
                question.signalCode(),
                question.candidateCode(),
                responseType,
                value,
                label,
                question.round(),
                question.id());
    }

    private List<DiagnosisCandidate> rescoreCandidates(
            List<DiagnosisCandidate> candidates,
            OnsiteQuestion question,
            AnsweredSignal answer) {
        int delta = onsiteScoreDelta(answer);
        List<DiagnosisCandidate> rescored = new ArrayList<>();
        for (DiagnosisCandidate candidate : candidates) {
            double score = candidate.supportScore();
            String explanation = candidate.explanation();
            List<String> evidenceIds = candidate.evidenceIds();
            if (candidate.code().equals(question.candidateCode())) {
                score = Math.max(0, Math.min(95, score + delta));
                explanation = "%s 现场第 %d 轮确认：%s。"
                        .formatted(
                                explanation,
                                question.round(),
                                answer.label());
                if (!Set.of("UNAVAILABLE", "SKIPPED")
                        .contains(answer.responseType())) {
                    evidenceIds = new ArrayList<>(evidenceIds);
                    evidenceIds.add("ONSITE-" + question.id());
                }
            }
            rescored.add(new DiagnosisCandidate(
                    candidate.code(),
                    candidate.label(),
                    candidate.rank(),
                    Math.round(score),
                    supportBand(score),
                    explanation,
                    List.copyOf(evidenceIds)));
        }
        rescored.sort(Comparator
                .comparingDouble(DiagnosisCandidate::supportScore)
                .reversed()
                .thenComparing(DiagnosisCandidate::rank));
        List<DiagnosisCandidate> ranked = new ArrayList<>();
        for (int index = 0; index < rescored.size(); index++) {
            DiagnosisCandidate candidate = rescored.get(index);
            ranked.add(new DiagnosisCandidate(
                    candidate.code(),
                    candidate.label(),
                    index + 1,
                    candidate.supportScore(),
                    candidate.supportBand(),
                    candidate.explanation(),
                    candidate.evidenceIds()));
        }
        return List.copyOf(ranked);
    }

    private int onsiteScoreDelta(AnsweredSignal answer) {
        if (Set.of("UNAVAILABLE", "SKIPPED").contains(answer.responseType())) {
            return 0;
        }
        if ("OTHER_TEXT".equals(answer.responseType())) {
            return 3;
        }
        Set<String> conflictingValues = Set.of(
                "NORMAL",
                "CLEAN",
                "CLEAR",
                "ABSENT",
                "NO",
                "OFF",
                "NOT_RUNNING");
        return conflictingValues.contains(answer.value()) ? -18 : 8;
    }

    private String supportBand(double score) {
        return score >= 80
                ? "STRONG_SUPPORT"
                : score >= 65 ? "SUPPORTED" : "NEEDS_CONFIRMATION";
    }

    private List<EvidenceGroup> appendOnsiteEvidence(
            List<EvidenceGroup> groups,
            OnsiteQuestion question,
            AnsweredSignal answer) {
        EvidenceItem onsiteEvidence = new EvidenceItem(
                "ONSITE-" + question.id(),
                "现场确认 · " + question.prompt(),
                "现场分析第 %d 轮".formatted(question.round()),
                "用户回答：%s".formatted(answer.label()),
                "USER_CONFIRMED",
                List.of(question.signalCode() + "=" + answer.value()));
        List<EvidenceGroup> result = new ArrayList<>();
        boolean replaced = false;
        for (EvidenceGroup group : groups) {
            if ("ONSITE_OBSERVATION".equals(group.type())) {
                List<EvidenceItem> items = new ArrayList<>(group.items());
                items.add(onsiteEvidence);
                result.add(new EvidenceGroup(
                        group.type(),
                        group.label(),
                        List.copyOf(items)));
                replaced = true;
            } else {
                result.add(group);
            }
        }
        if (!replaced) {
            result.add(0, new EvidenceGroup(
                    "ONSITE_OBSERVATION",
                    "现场确认事实",
                    List.of(onsiteEvidence)));
        }
        return List.copyOf(result);
    }

    private boolean isConverged(List<DiagnosisCandidate> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        double first = candidates.get(0).supportScore();
        double second = candidates.size() > 1
                ? candidates.get(1).supportScore()
                : 0;
        return first >= 75 && first - second >= 15;
    }

    private boolean hasSupportedCandidate(List<DiagnosisCandidate> candidates) {
        return candidates.stream()
                .anyMatch(candidate -> candidate.supportScore() >= 55);
    }

    private void insertDiagnosisSnapshot(DiagnosisSession session) {
        jdbcTemplate.update("""
                INSERT INTO diagnosis_snapshot_v1 (
                    session_key, understanding_key, stage,
                    status, payload_json
                ) VALUES (?, ?, ?, ?, CAST(? AS JSON))
                """,
                session.id(),
                session.problemUnderstanding().id(),
                session.stage(),
                session.status(),
                objectMapper.writeValueAsString(session));
    }

    private void updateDiagnosisSnapshot(DiagnosisSession session) {
        jdbcTemplate.update("""
                UPDATE diagnosis_snapshot_v1
                SET stage = ?, status = ?, payload_json = CAST(? AS JSON)
                WHERE session_key = ?
                """,
                session.stage(),
                session.status(),
                objectMapper.writeValueAsString(session),
                session.id());
    }

    private List<SavedReport> queryReports(String suffix, Object... arguments) {
        String sql = """
                SELECT report_key, session_key, report_name, note,
                       stage, diagnosis_status, snapshot_json, created_at
                FROM saved_diagnosis_report_v1
                %s
                """.formatted(suffix);
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> {
            DiagnosisSession snapshot = objectMapper.readValue(
                    resultSet.getString("snapshot_json"),
                    DiagnosisSession.class);
            String topCandidate = snapshot.candidates().isEmpty()
                    ? null
                    : snapshot.candidates().get(0).label();
            return new SavedReport(
                    resultSet.getString("report_key"),
                    resultSet.getString("session_key"),
                    resultSet.getString("report_name"),
                    resultSet.getString("note"),
                    resultSet.getString("stage"),
                    resultSet.getString("diagnosis_status"),
                    topCandidate,
                    resultSet.getTimestamp("created_at").toInstant(),
                    snapshot);
        }, arguments);
    }

    private String safeStrip(String value) {
        return value == null ? "" : value.strip();
    }

    private ProblemUnderstanding loadUnderstanding(String id) {
        List<String> rows = jdbcTemplate.query("""
                SELECT payload_json
                FROM problem_understanding_snapshot_v1
                WHERE understanding_key = ?
                """, (resultSet, rowNum) -> resultSet.getString(1), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "问题理解结果不存在。");
        }
        return objectMapper.readValue(rows.get(0), ProblemUnderstanding.class);
    }

    private List<RetrievedCase> retrieveStructuredCases(
            String model,
            String problemTypeCode) {
        return jdbcTemplate.query("""
                SELECT reception_id, model, problem_type_code,
                       problem_type_label, error_codes_json,
                       complaint, onsite_observation, cause_text,
                       action_text, final_resolved, first_fix,
                       visit_count, total_duration_minutes,
                       parts_json, source_reference, trust_level,
                       received_at
                FROM repair_case_projection_v1
                WHERE model = ?
                  AND problem_type_code = ?
                  AND final_resolved = TRUE
                ORDER BY first_fix DESC, received_at DESC, reception_id
                LIMIT 8
                """, this::mapCase, model, problemTypeCode);
    }

    private List<RetrievedCase> retrieveByReceptionIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = ids.stream()
                .map(id -> "?")
                .collect(Collectors.joining(","));
        return jdbcTemplate.query("""
                SELECT reception_id, model, problem_type_code,
                       problem_type_label, error_codes_json,
                       complaint, onsite_observation, cause_text,
                       action_text, final_resolved, first_fix,
                       visit_count, total_duration_minutes,
                       parts_json, source_reference, trust_level,
                       received_at
                FROM repair_case_projection_v1
                WHERE reception_id IN (%s)
                  AND final_resolved = TRUE
                """.formatted(placeholders),
                this::mapCase,
                ids.toArray());
    }

    private RetrievedCase mapCase(java.sql.ResultSet resultSet, int rowNum)
            throws java.sql.SQLException {
        return new RetrievedCase(
                resultSet.getString("reception_id"),
                resultSet.getString("model"),
                resultSet.getString("problem_type_code"),
                resultSet.getString("problem_type_label"),
                resultSet.getString("error_codes_json"),
                resultSet.getString("complaint"),
                resultSet.getString("onsite_observation"),
                resultSet.getString("cause_text"),
                resultSet.getString("action_text"),
                resultSet.getObject("final_resolved", Boolean.class),
                resultSet.getObject("first_fix", Boolean.class),
                resultSet.getInt("visit_count"),
                resultSet.getInt("total_duration_minutes"),
                resultSet.getString("parts_json"),
                resultSet.getString("source_reference"),
                resultSet.getString("trust_level"));
    }

    private List<RetrievedCase> mergeCases(
            List<RetrievedCase> structured,
            List<RetrievedCase> semantic) {
        Map<String, RetrievedCase> result = new LinkedHashMap<>();
        structured.forEach(item -> result.put(item.receptionId(), item));
        semantic.forEach(item -> result.putIfAbsent(item.receptionId(), item));
        return result.values().stream().limit(8).toList();
    }

    private List<DiagnosisCandidate> buildCandidates(
            ProblemUnderstanding understanding,
            List<RetrievedCase> cases,
            List<EvidenceItem> evidence) {
        if (cases.isEmpty() || "UNCLASSIFIED".equals(
                understanding.primaryProblemType().code())) {
            return List.of();
        }
        List<Hypothesis> hypotheses = jdbcTemplate.query("""
                SELECT ch.code, ch.name_zh, ch.default_rank
                FROM cause_hypothesis ch
                JOIN problem_type pt ON pt.id = ch.problem_type_id
                WHERE pt.code = ?
                ORDER BY ch.default_rank
                LIMIT 3
                """, (resultSet, rowNum) -> new Hypothesis(
                resultSet.getString("code"),
                resultSet.getString("name_zh"),
                resultSet.getInt("default_rank")),
                understanding.primaryProblemType().code());
        int verifiedCount = (int) cases.stream()
                .filter(item -> Boolean.TRUE.equals(item.finalResolved()))
                .count();
        List<String> evidenceIds = evidence.stream()
                .limit(3)
                .map(EvidenceItem::id)
                .toList();
        List<DiagnosisCandidate> candidates = new ArrayList<>();
        for (Hypothesis hypothesis : hypotheses) {
            double score = Math.min(
                    95,
                    48
                            + understanding.primaryProblemType().supportScore() * 0.25
                            + Math.min(16, verifiedCount * 2)
                            - (hypothesis.rank() - 1) * 11);
            if (score < 40) {
                continue;
            }
            String band = score >= 80
                    ? "STRONG_SUPPORT"
                    : score >= 65 ? "SUPPORTED" : "NEEDS_CONFIRMATION";
            candidates.add(new DiagnosisCandidate(
                    hypothesis.code(),
                    hypothesis.label(),
                    candidates.size() + 1,
                    Math.round(score),
                    band,
                    "与设备型号、问题分类及 %d 条历史维修记录一致；出发前可据此准备，换件前仍需完成现场确认。"
                            .formatted(verifiedCount),
                    evidenceIds));
        }
        return List.copyOf(candidates);
    }

    private List<EvidenceItem> buildCaseEvidence(List<RetrievedCase> cases) {
        return cases.stream().limit(4)
                .map(item -> new EvidenceItem(
                        "CASE-" + item.receptionId(),
                        "%s · %s".formatted(
                                item.receptionId(),
                                item.problemTypeLabel()),
                        item.sourceReference(),
                        truncate(
                                "现场现象：%s；原因记录：%s；处置：%s"
                                        .formatted(
                                                item.onsiteObservation(),
                                                item.causeText(),
                                                item.actionText()),
                                260),
                        Boolean.TRUE.equals(item.finalResolved())
                                ? "VERIFIED_CASE"
                                : "OBSERVED_CASE",
                        List.of(
                                "同型号 " + item.model(),
                                "同问题类型 " + item.problemTypeLabel(),
                                Boolean.TRUE.equals(item.firstFix())
                                        ? "首次到访解决"
                                        : "最终到访解决")))
                .toList();
    }

    private List<EvidenceItem> buildPartEvidence(List<RetrievedCase> cases) {
        Map<String, PartAggregate> aggregates = aggregateParts(cases);
        return aggregates.values().stream()
                .sorted(Comparator
                        .comparingInt(PartAggregate::caseCount)
                        .reversed()
                        .thenComparing(PartAggregate::partNumber))
                .limit(3)
                .map(part -> new EvidenceItem(
                        "PART-" + part.partNumber(),
                        "%s · %s".formatted(part.partNumber(), part.name()),
                        "03_Details of used parts.xlsx",
                        "在当前同型号、同问题类型的已解决案例中使用 %d 次，覆盖 %d 个维修事件。"
                                .formatted(part.quantity(), part.caseCount()),
                        "OBSERVED_CASE",
                        List.of("历史实际使用", "同型号案例")))
                .toList();
    }

    private List<PartRecommendation> buildPartRecommendations(
            List<RetrievedCase> cases,
            List<EvidenceItem> partEvidence) {
        Map<String, PartAggregate> aggregates = aggregateParts(cases);
        Map<String, String> evidenceByPart = partEvidence.stream()
                .collect(Collectors.toMap(
                        item -> item.id().replace("PART-", ""),
                        EvidenceItem::id));
        return aggregates.values().stream()
                .sorted(Comparator
                        .comparingInt(PartAggregate::caseCount)
                        .reversed()
                        .thenComparing(PartAggregate::partNumber))
                .limit(3)
                .map(part -> new PartRecommendation(
                        part.partNumber(),
                        part.name(),
                        part.caseCount() >= 3
                                ? "RECOMMENDED_PREPARE"
                                : "CONFIRM_ONSITE",
                        evidenceByPart.containsKey(part.partNumber())
                                ? List.of(evidenceByPart.get(part.partNumber()))
                                : List.of()))
                .toList();
    }

    private Map<String, PartAggregate> aggregateParts(List<RetrievedCase> cases) {
        Map<String, MutablePartAggregate> aggregate = new LinkedHashMap<>();
        for (RetrievedCase item : cases) {
            Set<String> seenInCase = new LinkedHashSet<>();
            for (Map<String, Object> part : readParts(item.partsJson())) {
                String number = String.valueOf(part.getOrDefault("partNumber", ""));
                if (number.isBlank()) {
                    continue;
                }
                MutablePartAggregate value = aggregate.computeIfAbsent(
                        number,
                        ignored -> new MutablePartAggregate(
                                number,
                                String.valueOf(part.getOrDefault("name", number))));
                value.quantity += number(part.get("quantity"));
                if (seenInCase.add(number)) {
                    value.caseCount++;
                }
            }
        }
        return aggregate.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new PartAggregate(
                        entry.getValue().partNumber,
                        entry.getValue().name,
                        entry.getValue().quantity,
                        entry.getValue().caseCount),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private List<ToolRecommendation> buildToolRecommendations(String problemTypeCode) {
        List<ToolRecommendation> tools = new ArrayList<>();
        tools.add(new ToolRecommendation("PPE", "绝缘手套与基础防护用品"));
        tools.add(new ToolRecommendation("MULTIMETER", "数字万用表"));
        if (problemTypeCode.contains("HIGH_PRESSURE")) {
            tools.add(new ToolRecommendation("PRESSURE_GAUGE", "制冷压力表组"));
            tools.add(new ToolRecommendation("CLEANING_SET", "冷凝器清洁工具"));
        } else {
            tools.add(new ToolRecommendation("THERMOMETER", "独立温度计"));
        }
        return List.copyOf(tools);
    }

    private List<RepairStep> buildRepairSteps(
            List<RetrievedCase> cases,
            List<EvidenceItem> evidence) {
        List<String> evidenceIds = evidence.stream()
                .limit(2)
                .map(EvidenceItem::id)
                .toList();
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        for (RetrievedCase item : cases) {
            String action = item.actionText() == null ? "" : item.actionText();
            for (String part : action.split("→|。|；")) {
                String normalized = part
                        .replaceAll("（.*?）", "")
                        .strip();
                if (normalized.length() >= 4) {
                    steps.add(normalized);
                }
            }
            if (steps.size() >= 5) {
                break;
            }
        }
        if (steps.isEmpty()) {
            return List.of();
        }
        List<RepairStep> result = new ArrayList<>();
        int sequence = 1;
        for (String step : steps.stream().limit(5).toList()) {
            result.add(new RepairStep(
                    sequence++,
                    step,
                    "HISTORICAL_ACTION",
                    evidenceIds));
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> readParts(String json) {
        try {
            return objectMapper.readValue(json, PARTS_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String stringField(ProblemUnderstanding understanding, String code) {
        return understanding.fields().stream()
                .filter(field -> field.code().equals(code))
                .map(field -> field.value() == null ? "" : String.valueOf(field.value()))
                .findFirst()
                .orElse("");
    }

    private String truncate(String value, int max) {
        String safe = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return safe.length() <= max ? safe : safe.substring(0, max - 1) + "…";
    }

    private record RetrievedCase(
            String receptionId,
            String model,
            String problemTypeCode,
            String problemTypeLabel,
            String errorCodesJson,
            String complaint,
            String onsiteObservation,
            String causeText,
            String actionText,
            Boolean finalResolved,
            Boolean firstFix,
            int visitCount,
            int totalDurationMinutes,
            String partsJson,
            String sourceReference,
            String trustLevel) {
    }

    private record Hypothesis(String code, String label, int rank) {
    }

    private record PartAggregate(
            String partNumber,
            String name,
            int quantity,
            int caseCount) {
    }

    private record AnsweredSignal(
            String field,
            String candidateCode,
            String responseType,
            String value,
            String label,
            int round,
            String questionId) {
    }

    private record OnsiteState(
            int currentRound,
            int maxRounds,
            List<AnsweredSignal> answers) {
    }

    private static class MutablePartAggregate {
        private final String partNumber;
        private final String name;
        private int quantity;
        private int caseCount;

        private MutablePartAggregate(String partNumber, String name) {
            this.partNumber = partNumber;
            this.name = name;
        }
    }
}
