package com.aifieldservice.repairassistant.retrieval;

import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.AnalysisProgress;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.DiagnosisCandidate;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.DiagnosisSession;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.EvidenceGroup;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.EvidenceItem;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.OnsiteQuestion;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.OnsiteQuestionResponseRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.PartRecommendation;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.PdfSourceRegion;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstanding;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.QuestionOption;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.Recommendations;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.RejectionRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.OnsiteRediagnosisRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.RepairStep;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.SaveReportRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.SavedReport;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.SourceDocumentLocation;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.StartDiagnosisRequest;
import static com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstandingRequest;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.integration.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.QdrantGateway;
import com.aifieldservice.repairassistant.knowledge.ProblemCatalogService;
import com.aifieldservice.repairassistant.diagnosis.ProblemUnderstandingService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 诊断主编排服务，也是当前 V1 的 Retrieval Planner 与 Reasoning Engine。
 *
 * <p>核心执行顺序是：
 * <ol>
 *   <li>读取已经确认的问题理解快照并执行 A 类信息门禁；</li>
 *   <li>按型号、问题类型从 MySQL 精确检索已解决案例；</li>
 *   <li>精确证据不足时才使用 OpenAI embedding + Qdrant 补充召回；</li>
 *   <li>只从已登记 cause hypothesis 中生成 0-3 个候选；</li>
 *   <li>由规则计算证据支持分，OpenAI 只润色第一候选的解释；</li>
 *   <li>进入现场后最多追问三轮，用新增事实重排候选并判断是否收敛。</li>
 * </ol>
 *
 * <p>因此 supportScore 表示“当前证据对候选的支持程度”，不是故障发生概率；
 * MySQL 是事实源，Qdrant 是召回索引，LLM 不是最终决策者。
 */
@Service
public class DiagnosisService {

    private static final TypeReference<List<Map<String, Object>>> PARTS_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<Map<String, Object>>> QUESTION_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<AnsweredSignal>> SIGNALS_TYPE =
            new TypeReference<>() {
            };

    /**
     * 常用现场字段的受控选项。选项 code 会进入评分规则，label 只用于界面展示。
     * 不在此表中的测量字段按数值输入处理，其余字段使用通用“异常/正常”选项。
     */
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

    /** 日文现场问题由受控字段映射生成，避免让 LLM 临时改写安全相关问题。 */
    private static final Map<String, String> QUESTION_PROMPTS_JA = Map.ofEntries(
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProblemCatalogService problemCatalog;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;
    private final ProblemUnderstandingService problemUnderstandingService;

    public DiagnosisService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ProblemCatalogService problemCatalog,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway,
            ProblemUnderstandingService problemUnderstandingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.problemCatalog = problemCatalog;
        this.openAiGateway = openAiGateway;
        this.qdrantGateway = qdrantGateway;
        this.problemUnderstandingService = problemUnderstandingService;
    }

    public DiagnosisSession start(StartDiagnosisRequest request) {
        ProblemUnderstanding understanding = loadUnderstanding(
                request.problemUnderstandingId());
        return runDiagnosis(understanding, "PRE_DEPARTURE", null);
    }

    private DiagnosisSession runDiagnosis(
            ProblemUnderstanding understanding,
            String stage,
            String parentSessionKey) {
        boolean japanese = isJapanese(understanding);

        // A 类字段缺失时在检索前阻断，避免用不完整上下文产生看似确定的建议。
        if (!understanding.readyForAnalysis()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    understanding.blockingMessage());
        }

        String model = stringField(understanding, "equipmentModel");
        String errorCode = stringField(understanding, "errorCode");
        String problemTypeCode = understanding.primaryProblemType().code();
        // SQL 精确检索是主路径：同型号、同问题类型、最终已解决的案例优先。
        List<RetrievedCase> cases = retrieveStructuredCases(model, problemTypeCode);
        // 服务手册同样先走结构化检索；错误码存在时必须精确匹配，避免跨报警引用。
        List<RetrievedManual> manuals = retrieveStructuredManuals(
                model,
                problemTypeCode,
                errorCode);

        // 任一来源的结构化证据不足时只生成一次 query embedding，随后分别补充案例和手册。
        // 两路向量查询都带型号、问题类型及来源硬过滤，不允许跨型号或跨知识类型补位。
        if ((cases.size() < 3 || manuals.isEmpty()) && openAiGateway.enabled()) {
            List<float[]> embeddings = openAiGateway.embed(
                    List.of(understanding.originalText()));
            if (!embeddings.isEmpty()) {
                if (cases.size() < 3) {
                    List<String> semanticReceptionIds = qdrantGateway
                            .search(embeddings.get(0), model, problemTypeCode, 8, 0.58)
                            .stream()
                            .map(QdrantGateway.SearchHit::receptionId)
                            .filter(id -> !id.isBlank())
                            .toList();
                    cases = mergeCases(
                            cases,
                            retrieveByReceptionIds(semanticReceptionIds));
                }
                if (manuals.isEmpty()) {
                    List<Long> manualIds = qdrantGateway.searchManual(
                            embeddings.get(0),
                            model,
                            problemTypeCode,
                            errorCode,
                            6,
                            0.55)
                            .stream()
                            .map(QdrantGateway.ManualSearchHit::manualKnowledgeId)
                            .toList();
                    manuals = mergeManuals(
                            manuals,
                            retrieveManualByIds(manualIds));
                }
            }
        }

        // 将内部案例转成前端可直接分类展示、可回溯来源的证据对象。
        List<EvidenceItem> caseEvidence = buildCaseEvidence(cases, japanese);
        List<EvidenceItem> manualEvidence = buildManualEvidence(manuals, japanese);
        List<EvidenceItem> partEvidence = buildPartEvidence(cases, japanese);
        List<EvidenceGroup> evidenceGroups = new ArrayList<>();
        if (!caseEvidence.isEmpty()) {
            evidenceGroups.add(new EvidenceGroup(
                    "REPAIR_CASE",
                    japanese ? "修理履歴" : "维修历史记录",
                    caseEvidence));
        }
        if (!manualEvidence.isEmpty()) {
            evidenceGroups.add(new EvidenceGroup(
                    "SERVICE_MANUAL",
                    japanese ? "サービスマニュアル" : "服务手册",
                    manualEvidence));
        }
        if (!partEvidence.isEmpty()) {
            evidenceGroups.add(new EvidenceGroup(
                    "PART_REFERENCE",
                    japanese ? "過去の使用部品" : "历史备件记录",
                    partEvidence));
        }

        // 没有合格案例或未识别问题类型时，buildCandidates 返回 0 个候选，不做强制补位。
        List<DiagnosisCandidate> candidates = buildCandidates(
                understanding,
                cases,
                manuals,
                caseEvidence,
                manualEvidence);
        if (!candidates.isEmpty()) {
            List<Map<String, Object>> candidatePrompt = candidates.stream()
                    .map(candidate -> Map.<String, Object>of(
                            "name", candidate.label(),
                            "supportScore", candidate.supportScore(),
                            "explanation", candidate.explanation()))
                    .toList();
            List<Map<String, Object>> evidencePrompt = java.util.stream.Stream
                    .concat(caseEvidence.stream(), manualEvidence.stream())
                    .map(evidence -> Map.<String, Object>of(
                            "id", evidence.id(),
                            "summary", evidence.summary(),
                            "trust", evidence.trustLabel()))
                    .toList();
            // 传给 LLM 的只有受控候选和检索证据；LLM 不能修改 code、分数或证据关系。
            Optional<String> aiSummary = openAiGateway.explainDiagnosis(
                    understanding.originalText(),
                    understanding.primaryProblemType().label(),
                    understanding.language(),
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

        // 备件和步骤来自入选历史案例，工具来自问题类型规则，不由 LLM 自由生成。
        Recommendations recommendations = new Recommendations(
                buildPartRecommendations(cases, partEvidence),
                buildToolRecommendations(problemTypeCode, japanese),
                buildRepairSteps(cases, caseEvidence, manuals, manualEvidence, japanese));
        // 状态描述证据充分度，而不是异步任务状态。当前计算在一次请求中同步完成。
        String status = candidates.isEmpty()
                ? "INSUFFICIENT_EVIDENCE"
                : caseEvidence.size() + manualEvidence.size() >= 2
                        ? "READY"
                        : "PARTIALLY_SUPPORTED";
        OnsiteQuestion nextQuestion = "ONSITE".equals(stage) && !candidates.isEmpty()
                ? nextQuestion(candidates, Set.of(), 1, japanese)
                : null;
        if (nextQuestion != null) {
            status = "ONSITE_QUESTIONING";
        }
        DiagnosisSession session = new DiagnosisSession(
                UUID.randomUUID().toString(),
                stage,
                status,
                new AnalysisProgress("GENERATING_EXPLANATION", 100),
                understanding,
                candidates,
                List.copyOf(evidenceGroups),
                recommendations,
                nextQuestion,
                Instant.now());
        // 保存展示快照，使报告、现场派生会话和回看都基于同一版诊断结果。
        insertDiagnosisSnapshot(session);
        if ("ONSITE".equals(stage) && parentSessionKey != null) {
            jdbcTemplate.update("""
                    INSERT INTO onsite_session_state_v1 (
                        session_key, parent_session_key, current_round,
                        max_rounds, answered_signals_json
                    ) VALUES (?, ?, 1, 3, CAST(? AS JSON))
                    """, session.id(), parentSessionKey, "[]");
        }
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

        // 同一个出发前会话重复点击“进入现场”时复用最近会话，避免产生平行现场状态。
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
        // 问题从当前候选的 clarification template 中选择，而不是让 LLM 临时自由提问。
        OnsiteQuestion question = parent.candidates().isEmpty()
                ? null
                : nextQuestion(
                        parent.candidates(),
                        Set.of(),
                        1,
                        isJapanese(parent.problemUnderstanding()));
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
        ensureNotRejected(session);
        if (!"ONSITE".equals(session.stage()) || session.nextQuestion() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前现场会话没有待回答问题。");
        }
        // questionId 同时承担乐观并发控制：旧页面不能回答已经更新的问题。
        if (!session.nextQuestion().id().equals(questionId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "现场问题已更新，请刷新后重新回答。");
        }

        OnsiteState state = loadOnsiteState(sessionId);
        // 先把多种 UI 输入归一为稳定的 AnsweredSignal，再进入统一评分逻辑。
        boolean japanese = isJapanese(session.problemUnderstanding());
        AnsweredSignal answer = normalizeAnswer(
                session.nextQuestion(),
                request,
                japanese);
        List<AnsweredSignal> answers = new ArrayList<>(state.answers());
        answers.add(answer);

        List<DiagnosisCandidate> candidates = rescoreCandidates(
                session.candidates(),
                session.nextQuestion(),
                answer,
                japanese);
        List<EvidenceGroup> evidenceGroups = appendOnsiteEvidence(
                session.evidenceGroups(),
                session.nextQuestion(),
                answer,
                japanese);

        int currentRound = state.currentRound();
        OnsiteQuestion next = null;
        String status;
        // 停止追问有三种原因：已收敛、达到最大轮数、没有新的有效问题。
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
            next = nextQuestion(
                    candidates,
                    answeredFields,
                    currentRound + 1,
                    japanese);
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
        // 更新的是现场会话快照；父级出发前快照保持不变，便于比较前后判断。
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

    @Transactional
    public ProblemUnderstanding prepareOnsiteRediagnosis(
            String sessionId,
            RejectionRequest request) {
        DiagnosisSession rejected = get(sessionId);
        if (!"ONSITE".equals(rejected.stage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only onsite diagnosis sessions can be rejected.");
        }
        ensureNotRejected(rejected);
        validateRejection(request);
        // 重新分析仅继承设备型号，避免旧描述、错误码等继续影响现场分类。
        String rediagnosisInput = buildRediagnosisInput(rejected.problemUnderstanding(), request);
        ProblemUnderstanding understanding = problemUnderstandingService.understand(
                new ProblemUnderstandingRequest(
                        "ONSITE",
                        rejected.problemUnderstanding().language(),
                        rediagnosisInput,
                        sessionId));
        return understanding;
    }

    @Transactional
    public DiagnosisSession startOnsiteRediagnosis(
            String sessionId,
            OnsiteRediagnosisRequest request) {
        DiagnosisSession rejected = get(sessionId);
        RejectionRequest rejection = request.rejection();
        if (!"ONSITE".equals(rejected.stage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only onsite diagnosis sessions can be rejected.");
        }
        ensureNotRejected(rejected);
        validateRejection(rejection);
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM onsite_rejection_v1 WHERE onsite_session_key = ?
                """, Integer.class, sessionId);
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This onsite diagnosis has already been rejected.");
        }

        ProblemUnderstanding understanding = loadUnderstanding(request.problemUnderstandingId());
        DiagnosisSession rediagnosed = runDiagnosis(understanding, "ONSITE", sessionId);

        DiagnosisSession terminal = new DiagnosisSession(
                rejected.id(), rejected.stage(), "REJECTED", rejected.progress(),
                rejected.problemUnderstanding(), rejected.candidates(), rejected.evidenceGroups(),
                rejected.recommendations(), null, Instant.now());
        int changed = jdbcTemplate.update("""
                UPDATE diagnosis_snapshot_v1
                SET status = ?, payload_json = CAST(? AS JSON)
                WHERE session_key = ? AND status <> 'REJECTED'
                """, terminal.status(), objectMapper.writeValueAsString(terminal), sessionId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This onsite diagnosis has already been rejected.");
        }
        jdbcTemplate.update("""
                INSERT INTO onsite_rejection_v1 (
                    onsite_session_key, rejected_session_key,
                    onsite_observation, rediagnosed_session_key
                ) VALUES (?, ?, ?, ?)
                """, sessionId, sessionId,
                safeStrip(rejection.onsiteObservation()), rediagnosed.id());
        return rediagnosed;
    }

    private String buildRediagnosisInput(
            ProblemUnderstanding originalUnderstanding,
            RejectionRequest request) {
        String observation = safeStrip(request.onsiteObservation());
        String equipmentModel = safeStrip(stringField(originalUnderstanding, "equipmentModel"));
        return "设备型号：%s\n\n现场实际发现：\n%s".formatted(equipmentModel, observation);
    }

    public SavedReport saveReport(String sessionId, SaveReportRequest request) {
        DiagnosisSession session = get(sessionId);
        ensureNotRejected(session);
        if (session.candidates().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前没有可保存的诊断候选。");
        }
        // 一个会话只允许保存一份报告，重复点击返回已有结果，接口天然幂等。
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
        // 保存完整不可变快照，而不是报告查看时重新执行诊断，避免结果随知识库变化漂移。
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
            int round,
            boolean japanese) {
        // 候选已经按支持分排序，因此优先选择最有可能改变当前决策的首位候选问题。
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
                String prompt = japanese
                        ? QUESTION_PROMPTS_JA.getOrDefault(
                                field,
                                "現場で「%s」を確認してください。".formatted(field))
                        : String.valueOf(
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
                    if (japanese) {
                        options = localizeQuestionOptions(options);
                    }
                } else if (isMeasurementField(field)) {
                    type = "MEASUREMENT";
                    unit = measurementUnit(field);
                    options = List.of();
                } else {
                    type = "SINGLE_CHOICE";
                    options = List.of(
                            new QuestionOption(
                                    "ABNORMAL",
                                    japanese ? "異常あり" : "存在异常"),
                            new QuestionOption(
                                    "NORMAL",
                                    japanese ? "正常" : "状态正常"));
                }
                // V1 一次只展示一个问题，用户回答后立即重算并决定是否继续追问。
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

    private List<QuestionOption> localizeQuestionOptions(List<QuestionOption> options) {
        return options.stream()
                .map(option -> new QuestionOption(
                        option.code(),
                        switch (option.code()) {
                            case "BLOCKED" -> "明らかな目詰まり";
                            case "DUSTY" -> "軽いほこり付着";
                            case "CLEAN" -> "清潔";
                            case "DAMAGED" -> "破損または空気漏れ";
                            case "STOPPED" -> "停止";
                            case "INTERMITTENT" -> "間欠運転";
                            case "PRESENT" -> "油跡あり";
                            case "ABSENT" -> "油跡なし";
                            case "YES" -> "はい";
                            case "NO" -> "いいえ";
                            case "RUNNING" -> "運転中";
                            case "NOT_RUNNING" -> "停止中";
                            case "HEAVY" -> "著しい着霜";
                            case "LIGHT" -> "軽い着霜";
                            case "ABNORMAL" -> "異常";
                            case "TRIPPED" -> "作動済み";
                            case "NORMAL" -> "正常";
                            default -> option.label();
                        }))
                .toList();
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
                // 状态 JSON 损坏时不复用不可确认的答案，但仍允许会话继续受控执行。
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
            OnsiteQuestionResponseRequest request,
            boolean japanese) {
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
        // 不同输入形式都转换为 field/value/label，后续评分不依赖前端控件类型。
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
                label = (japanese ? "測定値 " : "测量值 ") + value;
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
                label = japanese ? "現場では確認できない" : "现场暂时无法确认";
            }
            case "SKIPPED" -> {
                value = "SKIPPED";
                label = japanese ? "今回はスキップ" : "本轮已跳过";
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
            AnsweredSignal answer,
            boolean japanese) {
        int delta = onsiteScoreDelta(answer);
        List<DiagnosisCandidate> rescored = new ArrayList<>();
        for (DiagnosisCandidate candidate : candidates) {
            double score = candidate.supportScore();
            String explanation = candidate.explanation();
            List<String> evidenceIds = candidate.evidenceIds();
            // 当前问题绑定一个 candidate；V1 只直接调整该候选，随后重新排序全部候选。
            if (candidate.code().equals(question.candidateCode())) {
                // 95 分封顶，明确保留“不确定性”，避免 UI 显示为绝对正确。
                score = Math.max(0, Math.min(95, score + delta));
                explanation = japanese
                        ? "%s 現場確認（第%dラウンド）：%s。"
                                .formatted(
                                        explanation,
                                        question.round(),
                                        answer.label())
                        : "%s 现场第 %d 轮确认：%s。"
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
        // 支持分变化后重新计算 rank，确保界面顺序与当前证据一致。
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
        // 无法确认和跳过不应被当作支持或反证；自由文本暂只给予弱支持。
        if (Set.of("UNAVAILABLE", "SKIPPED").contains(answer.responseType())) {
            return 0;
        }
        if ("OTHER_TEXT".equals(answer.responseType())) {
            return 3;
        }
        // 这些“正常/不存在”值通常否定当前候选的异常信号，因此施加强反证扣分。
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
            AnsweredSignal answer,
            boolean japanese) {
        // 现场回答是用户确认事实，单独分组，不能与历史案例混成同一种证据来源。
        EvidenceItem onsiteEvidence = new EvidenceItem(
                "ONSITE-" + question.id(),
                (japanese ? "現場確認・" : "现场确认 · ") + question.prompt(),
                japanese
                        ? "現場分析・第%dラウンド".formatted(question.round())
                        : "现场分析第 %d 轮".formatted(question.round()),
                japanese
                        ? "回答：%s".formatted(answer.label())
                        : "用户回答：%s".formatted(answer.label()),
                "USER_CONFIRMED",
                List.of(question.signalCode() + "=" + answer.value()),
                null);
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
                    japanese ? "現場確認事実" : "现场确认事实",
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
        // 同时要求绝对支持度和相对领先，避免多个相近候选时过早停止追问。
        return first >= 75 && first - second >= 15;
    }

    private boolean hasSupportedCandidate(List<DiagnosisCandidate> candidates) {
        return candidates.stream()
                .anyMatch(candidate -> candidate.supportScore() >= 55);
    }

    private void insertDiagnosisSnapshot(DiagnosisSession session) {
        // snapshot 表是当前 V1 的应用读模型；规范化诊断实体表已在 V1 migration 中预留。
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
        // suffix 仅由本类固定调用点传入，不接受外部参数；业务值始终通过占位符绑定。
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

    private void ensureNotRejected(DiagnosisSession session) {
        if ("REJECTED".equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rejected onsite diagnosis sessions are terminal.");
        }
    }

    private void validateRejection(RejectionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rejection request is required.");
        }
        String observation = safeStrip(request.onsiteObservation());
        if (observation.isBlank() || observation.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "onsiteObservation is required and must not exceed 4000 characters.");
        }
    }

    /**
     * 语言属于问题理解快照，而不是浏览器临时状态。这样现场会话和保存报告
     * 始终沿用发起诊断时的业务语言，不会因为之后切换界面而混入另一种语言。
     */
    private boolean isJapanese(ProblemUnderstanding understanding) {
        return understanding != null && "ja-JP".equals(understanding.language());
    }

    private String localized(String chinese, String japanese, boolean useJapanese) {
        if (useJapanese && japanese != null && !japanese.isBlank()) {
            return japanese;
        }
        return chinese;
    }

    private String manualSourceReference(RetrievedManual manual, boolean japanese) {
        String printedPage = manual.printedPageLabel() == null
                || manual.printedPageLabel().isBlank()
                        ? "-"
                        : manual.printedPageLabel();
        return japanese
                ? "%s・PDF P%d・冊子 P%s・§%s".formatted(
                        manual.documentName(),
                        manual.pdfPageIndex(),
                        printedPage,
                        manual.sectionPath())
                : "%s · PDF P%d · 手册 P%s · §%s".formatted(
                        manual.documentName(),
                        manual.pdfPageIndex(),
                        printedPage,
                        manual.sectionPath());
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
        // SQL-first：硬过滤型号和问题类型，并排除未解决事件；首次修复成功和近期案例优先。
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
        // Qdrant 只返回业务 id，完整内容必须回到 MySQL 获取，保持唯一事实源。
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
        // 保留 SQL 结果顺序，向量结果只补缺；受付ID 是独立维修事件的去重键。
        Map<String, RetrievedCase> result = new LinkedHashMap<>();
        structured.forEach(item -> result.put(item.receptionId(), item));
        semantic.forEach(item -> result.putIfAbsent(item.receptionId(), item));
        return result.values().stream().limit(8).toList();
    }

    private List<RetrievedManual> retrieveStructuredManuals(
            String model,
            String problemTypeCode,
            String errorCode) {
        // 错误码为空时允许返回该问题类型的通用章节；存在错误码时只接受完全一致的章节。
        return jdbcTemplate.query("""
                SELECT id, document_name, model, problem_type_code,
                       knowledge_type, error_code, title, title_ja,
                       summary, summary_ja,
                       source_quote, source_anchor, source_region_json,
                       action_steps_json, action_steps_ja_json,
                       safety_warnings_json, safety_warnings_ja_json,
                       candidate_codes_json, source_reference,
                       pdf_page_index, printed_page_label,
                       section_path, trust_level
                FROM manual_knowledge_projection_v1
                WHERE model = ?
                  AND problem_type_code = ?
                  AND (? = '' OR error_code = ?)
                ORDER BY
                    CASE knowledge_type WHEN 'FAULT_DEFINITION' THEN 0 ELSE 1 END,
                    pdf_page_index, id
                LIMIT 6
                """, this::mapManual, model, problemTypeCode, errorCode, errorCode);
    }

    private List<RetrievedManual> retrieveManualByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = ids.stream()
                .map(id -> "?")
                .collect(Collectors.joining(","));
        return jdbcTemplate.query("""
                SELECT id, document_name, model, problem_type_code,
                       knowledge_type, error_code, title, title_ja,
                       summary, summary_ja,
                       source_quote, source_anchor, source_region_json,
                       action_steps_json, action_steps_ja_json,
                       safety_warnings_json, safety_warnings_ja_json,
                       candidate_codes_json, source_reference,
                       pdf_page_index, printed_page_label,
                       section_path, trust_level
                FROM manual_knowledge_projection_v1
                WHERE id IN (%s)
                """.formatted(placeholders), this::mapManual, ids.toArray());
    }

    private RetrievedManual mapManual(java.sql.ResultSet resultSet, int rowNum)
            throws java.sql.SQLException {
        return new RetrievedManual(
                resultSet.getLong("id"),
                resultSet.getString("document_name"),
                resultSet.getString("model"),
                resultSet.getString("problem_type_code"),
                resultSet.getString("knowledge_type"),
                resultSet.getString("error_code"),
                resultSet.getString("title"),
                resultSet.getString("title_ja"),
                resultSet.getString("summary"),
                resultSet.getString("summary_ja"),
                resultSet.getString("source_quote"),
                resultSet.getString("source_anchor"),
                readSourceRegion(resultSet.getString("source_region_json")),
                readStringList(resultSet.getString("action_steps_json")),
                readStringList(resultSet.getString("action_steps_ja_json")),
                readStringList(resultSet.getString("safety_warnings_json")),
                readStringList(resultSet.getString("safety_warnings_ja_json")),
                readStringList(resultSet.getString("candidate_codes_json")),
                resultSet.getString("source_reference"),
                resultSet.getInt("pdf_page_index"),
                resultSet.getString("printed_page_label"),
                resultSet.getString("section_path"),
                resultSet.getString("trust_level"));
    }

    private List<RetrievedManual> mergeManuals(
            List<RetrievedManual> structured,
            List<RetrievedManual> semantic) {
        Map<Long, RetrievedManual> result = new LinkedHashMap<>();
        structured.forEach(item -> result.put(item.id(), item));
        semantic.forEach(item -> result.putIfAbsent(item.id(), item));
        return result.values().stream().limit(6).toList();
    }

    private List<DiagnosisCandidate> buildCandidates(
            ProblemUnderstanding understanding,
            List<RetrievedCase> cases,
            List<RetrievedManual> manuals,
            List<EvidenceItem> caseEvidence,
            List<EvidenceItem> manualEvidence) {
        // 证据或领域分类缺失时允许返回 0 个候选，这是“可信优先”的产品约束。
        if ((cases.isEmpty() && manuals.isEmpty()) || "UNCLASSIFIED".equals(
                understanding.primaryProblemType().code())) {
            return List.of();
        }
        boolean japanese = isJapanese(understanding);
        // 候选只能来自已审查的 cause_hypothesis，不允许 LLM 临时创造根因。
        List<Hypothesis> hypotheses = jdbcTemplate.query("""
                SELECT ch.code, ch.name_zh, ch.name_ja, ch.default_rank
                FROM cause_hypothesis ch
                JOIN problem_type pt ON pt.id = ch.problem_type_id
                WHERE pt.code = ?
                ORDER BY ch.default_rank
                LIMIT 3
                """, (resultSet, rowNum) -> new Hypothesis(
                resultSet.getString("code"),
                japanese
                        ? resultSet.getString("name_ja")
                        : resultSet.getString("name_zh"),
                resultSet.getInt("default_rank")),
                understanding.primaryProblemType().code());
        int verifiedCount = (int) cases.stream()
                .filter(item -> Boolean.TRUE.equals(item.finalResolved()))
                .count();
        Map<Long, String> manualEvidenceIds = manualEvidence.stream()
                .collect(Collectors.toMap(
                        item -> Long.parseLong(item.id().replace("MANUAL-", "")),
                        EvidenceItem::id));
        List<DiagnosisCandidate> candidates = new ArrayList<>();
        for (Hypothesis hypothesis : hypotheses) {
            List<String> supportingManualIds = manuals.stream()
                    .filter(item -> item.candidateCodes().contains(hypothesis.code()))
                    .map(RetrievedManual::id)
                    .map(manualEvidenceIds::get)
                    .filter(java.util.Objects::nonNull)
                    .limit(3)
                    .toList();
            // V1 支持分由当前问题信号、历史已解决事件和官方手册交叉支持共同组成。
            // 该公式用于演示可解释排序，尚未使用真实标注集做概率校准。
            double score = Math.min(
                    95,
                    48
                            + understanding.primaryProblemType().supportScore() * 0.25
                            + Math.min(16, verifiedCount * 2)
                            + Math.min(12, supportingManualIds.size() * 4)
                            - (hypothesis.rank() - 1) * 11);
            if (score < 40) {
                continue;
            }
            String band = score >= 80
                    ? "STRONG_SUPPORT"
                    : score >= 65 ? "SUPPORTED" : "NEEDS_CONFIRMATION";
            List<String> evidenceIds = new ArrayList<>();
            caseEvidence.stream().limit(2).map(EvidenceItem::id).forEach(evidenceIds::add);
            evidenceIds.addAll(supportingManualIds);
            candidates.add(new DiagnosisCandidate(
                    hypothesis.code(),
                    hypothesis.label(),
                    candidates.size() + 1,
                    Math.round(score),
                    band,
                    japanese
                            ? "機器型式、問題分類、%d件の修理履歴、%d件の公式マニュアル根拠と整合しています。出発前準備に利用できますが、部品交換前に現場確認が必要です。"
                                    .formatted(verifiedCount, supportingManualIds.size())
                            : "与设备型号、问题分类、%d 条历史维修记录和 %d 条官方手册依据一致；出发前可据此准备，换件前仍需完成现场确认。"
                                    .formatted(verifiedCount, supportingManualIds.size()),
                    List.copyOf(evidenceIds)));
        }
        return List.copyOf(candidates);
    }

    private List<EvidenceItem> buildCaseEvidence(
            List<RetrievedCase> cases,
            boolean japanese) {
        // 限制四条是展示层约束；完整检索集仍用于候选和备件聚合。
        return cases.stream().limit(4)
                .map(item -> new EvidenceItem(
                        "CASE-" + item.receptionId(),
                        japanese
                                ? "%s・解決済み修理事例".formatted(item.receptionId())
                                : "%s · %s".formatted(
                                        item.receptionId(),
                                        item.problemTypeLabel()),
                        item.sourceReference(),
                        truncate(
                                japanese
                                        ? "現場状況：%s。原因記録：%s。処置：%s"
                                                .formatted(
                                                        item.onsiteObservation(),
                                                        item.causeText(),
                                                        item.actionText())
                                        : "现场现象：%s；原因记录：%s；处置：%s"
                                                .formatted(
                                                        item.onsiteObservation(),
                                                        item.causeText(),
                                                        item.actionText()),
                                260),
                        Boolean.TRUE.equals(item.finalResolved())
                                ? "VERIFIED_CASE"
                                : "OBSERVED_CASE",
                        List.of(
                                (japanese ? "同一型式 " : "同型号 ") + item.model(),
                                japanese ? "同一問題カテゴリ" : "同问题类型 " + item.problemTypeLabel(),
                                Boolean.TRUE.equals(item.firstFix())
                                        ? japanese ? "初回訪問で解決" : "首次到访解决"
                                        : japanese ? "最終訪問で解決" : "最终到访解决"),
                        null))
                .toList();
    }

    private List<EvidenceItem> buildManualEvidence(
            List<RetrievedManual> manuals,
            boolean japanese) {
        return manuals.stream().limit(4)
                .map(item -> new EvidenceItem(
                        "MANUAL-" + item.id(),
                        localized(item.title(), item.titleJa(), japanese),
                        manualSourceReference(item, japanese),
                        truncate(localized(item.summary(), item.summaryJa(), japanese), 280),
                        "AUTHORITATIVE",
                        List.of(
                                (japanese ? "適用型式 " : "适用型号 ") + item.model(),
                                item.errorCode() == null || item.errorCode().isBlank()
                                        ? japanese ? "同一問題カテゴリ" : "同问题类型"
                                        : (japanese ? "エラーコード " : "错误码 ")
                                                + item.errorCode(),
                                (japanese ? "章節 " : "章节 ") + item.sectionPath()),
                        new SourceDocumentLocation(
                                item.id(),
                                item.documentName(),
                                item.pdfPageIndex(),
                                item.printedPageLabel(),
                                item.sectionPath(),
                                item.sourceQuote(),
                                item.sourceAnchor(),
                                item.sourceRegion())))
                .toList();
    }

    private List<EvidenceItem> buildPartEvidence(
            List<RetrievedCase> cases,
            boolean japanese) {
        // 备件证据仅来自入选案例的实际使用记录，不从候选名称猜测部件。
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
                        japanese
                                ? "同一型式・同一問題カテゴリの解決済み事例で%d個使用され、%d件の修理に含まれています。"
                                        .formatted(part.quantity(), part.caseCount())
                                : "在当前同型号、同问题类型的已解决案例中使用 %d 次，覆盖 %d 个维修事件。"
                                        .formatted(part.quantity(), part.caseCount()),
                        "OBSERVED_CASE",
                        japanese
                                ? List.of("過去の実使用", "同一型式の事例")
                                : List.of("历史实际使用", "同型号案例"),
                        null))
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
            // quantity 统计总使用量，caseCount 统计独立事件数；同一事件重复使用不重复计案例。
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

    private List<ToolRecommendation> buildToolRecommendations(
            String problemTypeCode,
            boolean japanese) {
        // 工具是安全且稳定的领域规则。后续可迁移到数据库策略配置，当前不调用 LLM。
        List<ToolRecommendation> tools = new ArrayList<>();
        tools.add(new ToolRecommendation(
                "PPE",
                japanese ? "絶縁手袋・基本保護具" : "绝缘手套与基础防护用品"));
        tools.add(new ToolRecommendation(
                "MULTIMETER",
                japanese ? "デジタルマルチメータ" : "数字万用表"));
        if (problemTypeCode.contains("HIGH_PRESSURE")) {
            tools.add(new ToolRecommendation(
                    "PRESSURE_GAUGE",
                    japanese ? "冷媒圧力ゲージセット" : "制冷压力表组"));
            tools.add(new ToolRecommendation(
                    "CLEANING_SET",
                    japanese ? "凝縮器清掃工具" : "冷凝器清洁工具"));
        } else {
            tools.add(new ToolRecommendation(
                    "THERMOMETER",
                    japanese ? "独立温度計" : "独立温度计"));
        }
        return List.copyOf(tools);
    }

    private List<RepairStep> buildRepairSteps(
            List<RetrievedCase> cases,
            List<EvidenceItem> caseEvidence,
            List<RetrievedManual> manuals,
            List<EvidenceItem> manualEvidence,
            boolean japanese) {
        List<String> caseEvidenceIds = caseEvidence.stream()
                .limit(2)
                .map(EvidenceItem::id)
                .toList();
        Map<Long, String> manualEvidenceIds = manualEvidence.stream()
                .collect(Collectors.toMap(
                        item -> Long.parseLong(item.id().replace("MANUAL-", "")),
                        EvidenceItem::id));
        // 已解决案例仍是处置优先来源；服务手册在其后补足标准检查与安全步骤。
        LinkedHashMap<String, RepairStepSource> steps = new LinkedHashMap<>();
        for (RetrievedCase item : cases) {
            String action = item.actionText() == null ? "" : item.actionText();
            for (String part : action.split("→|。|；")) {
                String normalized = part
                        .replaceAll("（.*?）", "")
                        .strip();
                if (normalized.length() >= 4) {
                    steps.putIfAbsent(
                            normalized,
                            new RepairStepSource(
                                    japanese ? "修理履歴" : "HISTORICAL_ACTION",
                                    caseEvidenceIds));
                }
            }
            // 最多保留前三条历史处置，为官方检查流程预留至少两个展示位置。
            if (steps.size() >= 3) {
                break;
            }
        }
        // 生成行动清单时先使用正式维修流程，再补充故障定义中的复位说明。
        // 证据面板仍保留“定义优先”的阅读顺序，两种排序服务于不同用户任务。
        List<RetrievedManual> manualsForSteps = manuals.stream()
                .sorted(Comparator
                        .comparingInt((RetrievedManual item) ->
                                "REPAIR_PROCEDURE".equals(item.knowledgeType()) ? 0 : 1)
                        .thenComparingInt(RetrievedManual::pdfPageIndex))
                .toList();
        for (RetrievedManual manual : manualsForSteps) {
            String evidenceId = manualEvidenceIds.get(manual.id());
            List<String> localizedSteps = japanese
                    ? manual.actionStepsJa()
                    : manual.actionSteps();
            for (String action : localizedSteps) {
                String normalized = action.replaceAll("\\s+", " ").strip();
                if (normalized.length() >= 4) {
                    steps.putIfAbsent(
                            normalized,
                            new RepairStepSource(
                                    japanese ? "サービスマニュアル" : "SERVICE_MANUAL",
                                    evidenceId == null ? List.of() : List.of(evidenceId)));
                }
                if (steps.size() >= 5) {
                    break;
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
        for (Map.Entry<String, RepairStepSource> step
                : steps.entrySet().stream().limit(5).toList()) {
            result.add(new RepairStep(
                    sequence++,
                    step.getKey(),
                    step.getValue().sourceLabel(),
                    step.getValue().evidenceIds()));
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

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private PdfSourceRegion readSourceRegion(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PdfSourceRegion.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid PDF source region JSON", exception);
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

    private record RetrievedManual(
            long id,
            String documentName,
            String model,
            String problemTypeCode,
            String knowledgeType,
            String errorCode,
            String title,
            String titleJa,
            String summary,
            String summaryJa,
            String sourceQuote,
            String sourceAnchor,
            PdfSourceRegion sourceRegion,
            List<String> actionSteps,
            List<String> actionStepsJa,
            List<String> safetyWarnings,
            List<String> safetyWarningsJa,
            List<String> candidateCodes,
            String sourceReference,
            int pdfPageIndex,
            String printedPageLabel,
            String sectionPath,
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

    private record RepairStepSource(String sourceLabel, List<String> evidenceIds) {
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
