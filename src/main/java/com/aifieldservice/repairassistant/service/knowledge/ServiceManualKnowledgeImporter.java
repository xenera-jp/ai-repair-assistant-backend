package com.aifieldservice.repairassistant.service.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.ManualDocument;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.ManualUnit;
import com.aifieldservice.repairassistant.integration.openai.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.qdrant.QdrantGateway;
import com.aifieldservice.repairassistant.integration.qdrant.QdrantGateway.VectorPoint;
import com.aifieldservice.repairassistant.dao.knowledge.IngestionBatchMapper;
import com.aifieldservice.repairassistant.dao.knowledge.KnowledgeBaseMapper;
import com.aifieldservice.repairassistant.dao.knowledge.KnowledgeUnitMapper;
import com.aifieldservice.repairassistant.dao.knowledge.ManualKnowledgeImportMapper;
import com.aifieldservice.repairassistant.domain.knowledge.command.ManualProjectionCommand;
import com.aifieldservice.repairassistant.dao.knowledge.SourceFileMapper;
import com.aifieldservice.repairassistant.domain.knowledge.command.SourceFileCommand;
import com.aifieldservice.repairassistant.service.knowledge.parser.ServiceManualParser;

import tools.jackson.databind.ObjectMapper;

/**
 * 固定服务手册知识包的离线构建入口。
 *
 * <p>导入器只调度已经审查过的 Profile。每份 PDF 按 SHA 和 Parser Version 独立判断，
 * 新增手册不会触发既有手册重复发布。原始页、标准知识对象和检索投影分别落库，
 * 在线诊断只读取已发布知识，同时仍能回溯到原始 PDF 页和证据坐标。
 */
@Component
@Order(20)
public class ServiceManualKnowledgeImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceManualKnowledgeImporter.class);
    private static final String KNOWLEDGE_BASE_CODE = "AI_REPAIR_DEMO";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IngestionBatchMapper ingestionBatchMapper;
    private final SourceFileMapper sourceFileMapper;
    private final KnowledgeUnitMapper knowledgeUnitMapper;
    private final ManualKnowledgeImportMapper manualKnowledgeImportMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final RepairAssistantProperties properties;
    private final List<ServiceManualParser> parsers;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;

    public ServiceManualKnowledgeImporter(
            KnowledgeBaseMapper knowledgeBaseMapper,
            IngestionBatchMapper ingestionBatchMapper,
            SourceFileMapper sourceFileMapper,
            KnowledgeUnitMapper knowledgeUnitMapper,
            ManualKnowledgeImportMapper manualKnowledgeImportMapper,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            RepairAssistantProperties properties,
            List<ServiceManualParser> parsers,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.ingestionBatchMapper = ingestionBatchMapper;
        this.sourceFileMapper = sourceFileMapper;
        this.knowledgeUnitMapper = knowledgeUnitMapper;
        this.manualKnowledgeImportMapper = manualKnowledgeImportMapper;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.parsers = List.copyOf(parsers);
        this.openAiGateway = openAiGateway;
        this.qdrantGateway = qdrantGateway;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.knowledge().importEnabled()) {
            return;
        }
        Path sourceDirectory = Path.of(properties.knowledge().sourcePath()).toAbsolutePath();
        if (!Files.isDirectory(sourceDirectory)) {
            return;
        }
        try {
            List<ManualSource> sources = findSupportedManuals(sourceDirectory);
            if (sources.isEmpty()) {
                log.info("No reviewed service manual profile found under {}", sourceDirectory);
                return;
            }

            for (ManualSource source : sources) {
                try {
                    ManualDocument manual = source.parser().parse(source.path());
                    String fileSha = sha256(Files.readAllBytes(source.path()));
                    if (!needsImport(fileSha, manual)) {
                        continue;
                    }
                    transactionTemplate.executeWithoutResult(
                            status -> importManual(source.path(), manual, fileSha));
                    log.info(
                            "Service manual import completed: {} ({} pages, {} knowledge units)",
                            manual.documentName(),
                            manual.pageCount(),
                            manual.units().size());
                } catch (Exception exception) {
                    // 单份资料失败不应阻止其他知识包和在线服务启动。
                    log.error("Service manual import failed: {}", source.path(), exception);
                }
            }
            indexPendingManualKnowledge();
        } catch (Exception exception) {
            log.error("Unable to scan reviewed service manuals", exception);
        }
    }

    private List<ManualSource> findSupportedManuals(Path sourceDirectory) throws Exception {
        try (var files = Files.list(sourceDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(path -> matchParser(path))
                    .flatMap(Optional::stream)
                    .toList();
        }
    }

    private Optional<ManualSource> matchParser(Path path) {
        List<ServiceManualParser> matches = parsers.stream()
                .filter(parser -> parser.supports(path))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Service manual must match exactly one profile: " + path);
        }
        return Optional.of(new ManualSource(path, matches.get(0)));
    }

    private boolean needsImport(String fileSha, ManualDocument manual) {
        return manualKnowledgeImportMapper.countPublished(KNOWLEDGE_BASE_CODE, fileSha,
                manual.parserVersion()) < manual.units().size();
    }

    private void importManual(Path path, ManualDocument manual, String fileSha) {
        try {
            long knowledgeBaseId = ensureKnowledgeBase();
            long batchId = createBatch(knowledgeBaseId);
            long sourceFileId = registerSourceFile(
                    knowledgeBaseId,
                    batchId,
                    path,
                    fileSha,
                    manual);

            Map<Integer, Long> sourcePages = new LinkedHashMap<>();
            for (ManualUnit unit : manual.units()) {
                long problemTypeId = requireId(manualKnowledgeImportMapper.findProblemTypeId(unit.problemTypeCode()), "problem type");
                long sourceRecordId = sourcePages.computeIfAbsent(
                        unit.sourcePage().pdfPageIndex(),
                        page -> insertSourcePage(sourceFileId, manual, unit));
                publishKnowledgeUnit(
                        knowledgeBaseId,
                        problemTypeId,
                        sourceRecordId,
                        manual,
                        unit,
                        fileSha);
            }

            sourceFileMapper.markValidated(sourceFileId);
            ingestionBatchMapper.complete(batchId, manual.units().size());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish service manual knowledge", exception);
        }
    }

    private long ensureKnowledgeBase() {
        knowledgeBaseMapper.upsertActive(KNOWLEDGE_BASE_CODE, "AI Repair Assistant Demo Knowledge");
        return requireId(knowledgeBaseMapper.findIdByCode(KNOWLEDGE_BASE_CODE), "knowledge base");
    }

    private long createBatch(long knowledgeBaseId) {
        String batchKey = UUID.randomUUID().toString();
        ingestionBatchMapper.insert(knowledgeBaseId, batchKey, 1);
        return requireId(ingestionBatchMapper.findIdByBatchKey(batchKey), "ingestion batch");
    }

    private long registerSourceFile(
            long knowledgeBaseId,
            long batchId,
            Path path,
            String fileSha,
            ManualDocument manual) throws Exception {
        sourceFileMapper.upsert(new SourceFileCommand(knowledgeBaseId, batchId, manual.logicalDocumentKey(),
                path.getFileName().toString(), "PDF", "SERVICE_MANUAL", "en-US", fileSha,
                Files.size(path), manual.parserVersion(), true));
        return requireId(sourceFileMapper.findIdByKnowledgeBaseAndSha(knowledgeBaseId, fileSha), "source file");
    }

    private long insertSourcePage(
            long sourceFileId,
            ManualDocument manual,
            ManualUnit unit) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("documentName", manual.documentName());
        locator.put("pdfPageIndex", unit.sourcePage().pdfPageIndex());
        locator.put("printedPageLabel", unit.sourcePage().printedPageLabel());
        locator.put("sectionPath", unit.sectionPath());
        locator.put("rawText", unit.sourcePage().text());
        String rawJson = writeJson(locator);
        String businessKey = manual.model() + ":SERVICE:PDF_PAGE:"
                + unit.sourcePage().pdfPageIndex();
        manualKnowledgeImportMapper.upsertPage(sourceFileId, businessKey,
                unit.sourcePage().pdfPageIndex(), "PAGE:" + unit.sourcePage().pdfPageIndex(), rawJson,
                sha256(rawJson.getBytes(StandardCharsets.UTF_8)));
        return requireId(manualKnowledgeImportMapper.findPageId(sourceFileId, businessKey), "manual source page");
    }

    private void publishKnowledgeUnit(
            long knowledgeBaseId,
            long problemTypeId,
            long sourceRecordId,
            ManualDocument manual,
            ManualUnit unit,
            String fileSha) {
        String pointId = UUID.nameUUIDFromBytes(
                ("manual:" + unit.unitKey()).getBytes(StandardCharsets.UTF_8))
                .toString();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("manufacturer", manual.manufacturer());
        content.put("model", manual.model());
        content.put("problemTypeCode", unit.problemTypeCode());
        content.put("errorCode", unit.errorCode());
        content.put("interpretations", Map.of(
                "zh-CN", Map.of(
                        "title", unit.title(),
                        "summary", unit.summary(),
                        "actionSteps", unit.actionSteps(),
                        "safetyWarnings", unit.safetyWarnings()),
                "ja-JP", Map.of(
                        "title", unit.titleJa(),
                        "summary", unit.summaryJa(),
                        "actionSteps", unit.actionStepsJa(),
                        "safetyWarnings", unit.safetyWarningsJa())));
        content.put("sourceQuote", unit.sourceQuote());
        content.put("sourceAnchor", unit.sourceAnchor());
        content.put("sourceRegion", unit.sourceRegion());
        content.put("actionSteps", unit.actionSteps());
        content.put("safetyWarnings", unit.safetyWarnings());
        content.put("candidateCodes", unit.candidateCodes());
        content.put("source", Map.of(
                "documentName", manual.documentName(),
                "pdfPageIndex", unit.sourcePage().pdfPageIndex(),
                "printedPageLabel", nullable(unit.sourcePage().printedPageLabel()),
                "sectionPath", unit.sectionPath()));
        String contentJson = writeJson(content);
        String contentFingerprint = sha256(contentJson.getBytes(StandardCharsets.UTF_8));
        String sourceFingerprint = sha256((fileSha + ":" + unit.sourcePage().pdfPageIndex()
                + ":" + unit.sectionPath()).getBytes(StandardCharsets.UTF_8));

        knowledgeUnitMapper.upsert(knowledgeBaseId, unit.unitKey(), unit.unitType());
        long unitId = requireId(knowledgeUnitMapper.findId(knowledgeBaseId, unit.unitKey()), "knowledge unit");

        manualKnowledgeImportMapper.upsertVersion(unitId, unit.titleJa(), contentJson,
                sourceFingerprint, contentFingerprint, pointId);
        long versionId = requireId(manualKnowledgeImportMapper.findVersionId(unitId), "knowledge unit version");
        manualKnowledgeImportMapper.linkProblemType(versionId, problemTypeId);
        manualKnowledgeImportMapper.upsertSourceLink(versionId, sourceRecordId);
        // 语义索引使用日文业务解释；英文原文仍通过 source_quote 独立保留。
        upsertProjection(versionId, "PROBLEM", unit.problemProjectionJa());
        upsertProjection(versionId, "RESOLUTION", unit.resolutionProjectionJa());

        String sourceReference = "%s · PDF P%d · 手册 P%s · §%s".formatted(
                manual.documentName(),
                unit.sourcePage().pdfPageIndex(),
                Optional.ofNullable(unit.sourcePage().printedPageLabel()).orElse("-"),
                unit.sectionPath());
        manualKnowledgeImportMapper.upsertManualProjection(new ManualProjectionCommand(versionId,
                manual.documentName(), manual.manufacturer(), manual.model(), unit.problemTypeCode(),
                unit.unitType(), unit.errorCode(), unit.title(), unit.titleJa(), unit.summary(), unit.summaryJa(),
                unit.sourceQuote(), unit.sourceAnchor(), writeJson(unit.sourceRegion()), writeJson(unit.actionSteps()),
                writeJson(unit.actionStepsJa()), writeJson(unit.safetyWarnings()), writeJson(unit.safetyWarningsJa()),
                writeJson(unit.candidateCodes()), sourceReference, unit.sourcePage().pdfPageIndex(),
                unit.sourcePage().printedPageLabel(), unit.sectionPath(), unit.problemProjection(),
                unit.problemProjectionJa(), unit.resolutionProjection(), unit.resolutionProjectionJa(), pointId));
    }

    private void upsertProjection(long versionId, String type, String text) {
        manualKnowledgeImportMapper.upsertSearchProjection(versionId, type, text,
                sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void indexPendingManualKnowledge() {
        if (!openAiGateway.enabled()) {
            log.info("OpenAI key is not configured; manual semantic indexing is deferred");
            return;
        }
        List<PendingManualVector> pending = manualKnowledgeImportMapper.findPendingVectors().stream()
                .map(row -> new PendingManualVector(row.id(), row.model(), row.problemTypeCode(),
                        row.errorCode(), row.knowledgeType(), row.problemProjection(), row.qdrantPointId()))
                .toList();
        for (int offset = 0; offset < pending.size(); offset += 64) {
            List<PendingManualVector> batch = pending.subList(
                    offset,
                    Math.min(offset + 64, pending.size()));
            List<float[]> vectors = openAiGateway.embed(
                    batch.stream().map(PendingManualVector::projection).toList());
            if (vectors.size() != batch.size()) {
                return;
            }
            List<VectorPoint> points = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                PendingManualVector item = batch.get(index);
                points.add(new VectorPoint(
                        item.pointId(),
                        vectors.get(index),
                        Map.of(
                                "knowledgeSource", "SERVICE_MANUAL",
                                "manualKnowledgeId", item.id(),
                                "model", item.model(),
                                "problemTypeCode", item.problemTypeCode(),
                                "errorCode", item.errorCode(),
                                "knowledgeType", item.knowledgeType())));
            }
            if (!qdrantGateway.upsert(points)) {
                return;
            }
            manualKnowledgeImportMapper.markIndexed(batch.stream().map(PendingManualVector::id).toList());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize manual knowledge", exception);
        }
    }

    private long requireId(Long id, String resource) {
        if (id == null) {
            throw new IllegalStateException("Unable to resolve " + resource + " after persistence");
        }
        return id;
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private record PendingManualVector(
            long id,
            String model,
            String problemTypeCode,
            String errorCode,
            String knowledgeType,
            String projection,
            String pointId) {
    }

    private record ManualSource(Path path, ServiceManualParser parser) {
    }
}
