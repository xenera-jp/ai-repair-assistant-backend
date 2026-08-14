package com.aifieldservice.repairassistant.service.diagnosis.impl;

import com.aifieldservice.repairassistant.service.diagnosis.*;
import com.aifieldservice.repairassistant.service.diagnosis.DiagnosisService.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.domain.diagnosis.command.StartDiagnosisRequest;
import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.integration.openai.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.qdrant.QdrantGateway;
import com.aifieldservice.repairassistant.dao.diagnosis.CauseHypothesisMapper;
import com.aifieldservice.repairassistant.dao.diagnosis.DiagnosisSnapshotMapper;
import com.aifieldservice.repairassistant.domain.retrieval.model.ManualKnowledgeProjection;
import com.aifieldservice.repairassistant.domain.retrieval.model.RepairCaseProjection;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService;
import com.aifieldservice.repairassistant.service.diagnosis.ProblemUnderstandingService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteQuestionFactory;
import com.aifieldservice.repairassistant.service.retrieval.RetrievalService;

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
public class DiagnosisServiceImpl implements DiagnosisService {

    private static final TypeReference<List<Map<String, Object>>> PARTS_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {
            };
    private final DiagnosisSnapshotMapper diagnosisSnapshotMapper;
    private final CauseHypothesisMapper causeHypothesisMapper;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;
    private final ProblemCatalogService problemCatalog;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;
    private final ProblemUnderstandingService problemUnderstandingService;
    private final OnsiteQuestionFactory onsiteQuestionFactory;

    public DiagnosisServiceImpl(
            DiagnosisSnapshotMapper diagnosisSnapshotMapper,
            CauseHypothesisMapper causeHypothesisMapper,
            RetrievalService retrievalService,
            ObjectMapper objectMapper,
            ProblemCatalogService problemCatalog,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway,
            ProblemUnderstandingService problemUnderstandingService,
            OnsiteQuestionFactory onsiteQuestionFactory) {
        this.diagnosisSnapshotMapper = diagnosisSnapshotMapper;
        this.causeHypothesisMapper = causeHypothesisMapper;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
        this.problemCatalog = problemCatalog;
        this.openAiGateway = openAiGateway;
        this.qdrantGateway = qdrantGateway;
        this.problemUnderstandingService = problemUnderstandingService;
        this.onsiteQuestionFactory = onsiteQuestionFactory;
    }

    @Transactional
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
                ? onsiteQuestionFactory.create(candidates, Set.of(), 1, japanese)
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
        return session;
    }

    /** Entry point for the onsite module after it has validated a rejection workflow. */
    @Transactional
    public DiagnosisSession startOnsiteDiagnosis(
            ProblemUnderstanding understanding,
            String parentSessionKey) {
        return runDiagnosis(understanding, "ONSITE", parentSessionKey);
    }

    public OnsiteQuestion createInitialOnsiteQuestion(
            List<DiagnosisCandidate> candidates,
            Set<String> answeredFields,
            int round,
            ProblemUnderstanding understanding) {
        return onsiteQuestionFactory.create(
                candidates, answeredFields, round, isJapanese(understanding));
    }

    @Transactional(readOnly = true)
    public DiagnosisSession get(String sessionId) {
        String payload = diagnosisSnapshotMapper.findPayloadBySessionKey(sessionId);
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "诊断会话不存在。");
        }
        return objectMapper.readValue(payload, DiagnosisSession.class);
    }

    private void insertDiagnosisSnapshot(DiagnosisSession session) {
        diagnosisSnapshotMapper.insert(
                session.id(),
                session.problemUnderstanding().id(),
                session.stage(),
                session.status(),
                objectMapper.writeValueAsString(session));
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
        return problemUnderstandingService.get(id);
    }

    private List<RetrievedCase> retrieveStructuredCases(
            String model,
            String problemTypeCode) {
        // SQL-first：硬过滤型号和问题类型，并排除未解决事件；首次修复成功和近期案例优先。
        return retrievalService.findResolvedCases(model, problemTypeCode).stream()
                .map(this::toRetrievedCase)
                .toList();
    }

    private List<RetrievedCase> retrieveByReceptionIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // Qdrant 只返回业务 id，完整内容必须回到 MySQL 获取，保持唯一事实源。
        return retrievalService.findCasesByReceptionIds(ids).stream()
                .map(this::toRetrievedCase)
                .toList();
    }

    private RetrievedCase toRetrievedCase(RepairCaseProjection row) {
        return new RetrievedCase(
                row.receptionId(), row.model(), row.problemTypeCode(), row.problemTypeLabel(),
                row.errorCodesJson(), row.complaint(), row.onsiteObservation(), row.causeText(),
                row.actionText(), row.finalResolved(), row.firstFix(), row.visitCount(),
                row.totalDurationMinutes(), row.partsJson(), row.sourceReference(), row.trustLevel());
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
        // 明确错误码章节优先，同一问题类型的通用诊断章节可作补充。
        // 这保证 E6 等精确元数据命中不被语义检索结果覆盖，同时让
        // 没有错误码的官方检查流程仍可被召回。
        return retrievalService.findStructuredManuals(model, problemTypeCode, errorCode)
                .stream().map(this::toRetrievedManual).toList();
    }

    private List<RetrievedManual> retrieveManualByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return retrievalService.findManualsByIds(ids).stream()
                .map(this::toRetrievedManual).toList();
    }

    private RetrievedManual toRetrievedManual(ManualKnowledgeProjection row) {
        return new RetrievedManual(
                row.id(), row.documentName(), row.model(), row.problemTypeCode(), row.knowledgeType(),
                row.errorCode(), row.title(), row.titleJa(), row.summary(), row.summaryJa(),
                row.sourceQuote(), row.sourceAnchor(), readSourceRegion(row.sourceRegionJson()),
                readStringList(row.actionStepsJson()), readStringList(row.actionStepsJaJson()),
                readStringList(row.safetyWarningsJson()), readStringList(row.safetyWarningsJaJson()),
                readStringList(row.candidateCodesJson()), row.sourceReference(), row.pdfPageIndex(),
                row.printedPageLabel(), row.sectionPath(), row.trustLevel());
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
        List<Hypothesis> hypotheses = causeHypothesisMapper
                .findByProblemTypeCode(understanding.primaryProblemType().code())
                .stream()
                .limit(3)
                .map(row -> new Hypothesis(
                        row.code(),
                        japanese ? row.nameJa() : row.nameZh(),
                        row.defaultRank()))
                .toList();
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
