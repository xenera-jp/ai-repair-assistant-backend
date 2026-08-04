package com.aifieldservice.repairassistant.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.integration.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.QdrantGateway;
import com.aifieldservice.repairassistant.integration.QdrantGateway.VectorPoint;
import com.aifieldservice.repairassistant.knowledge.RirSsbServiceManualParser.ManualKnowledge;
import com.aifieldservice.repairassistant.knowledge.RirSsbServiceManualParser.ParsedManual;

import tools.jackson.databind.ObjectMapper;

/**
 * 固定服务手册的离线知识构建入口。
 *
 * <p>V1 只启用已经审查过的 RIR1-SSB Profile。原始 PDF、页面文本、标准知识对象和
 * 检索投影分别落库，使在线诊断只读取可发布知识，同时仍能回溯到原始 PDF 页。
 */
@Component
@Order(20)
public class ServiceManualKnowledgeImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceManualKnowledgeImporter.class);
    private static final String KNOWLEDGE_BASE_CODE = "AI_REPAIR_DEMO";
    private static final String PARSER_VERSION = "PDFBOX_RIR1_SSB_V1";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final RepairAssistantProperties properties;
    private final RirSsbServiceManualParser parser;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;

    public ServiceManualKnowledgeImporter(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            RepairAssistantProperties properties,
            RirSsbServiceManualParser parser,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.parser = parser;
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
            Optional<Path> source = findSupportedManual(sourceDirectory);
            if (source.isEmpty()) {
                log.info("No reviewed service manual profile found under {}", sourceDirectory);
                return;
            }

            int published = Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM manual_knowledge_projection_v1",
                    Integer.class)).orElse(0);
            int missingSourceLocation = Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM manual_knowledge_projection_v1 "
                            + "WHERE source_quote IS NULL OR source_anchor IS NULL "
                            + "OR source_region_json IS NULL OR title_ja IS NULL "
                            + "OR summary_ja IS NULL OR action_steps_ja_json IS NULL",
                    Integer.class)).orElse(0);
            if (published == 0 || missingSourceLocation > 0) {
                ParsedManual manual = parser.parse(source.get());
                transactionTemplate.executeWithoutResult(status -> importManual(source.get(), manual));
                log.info(
                        "Service manual import completed: {} pages, {} knowledge units",
                        manual.pageCount(),
                        manual.units().size());
            }
            indexPendingManualKnowledge();
        } catch (Exception exception) {
            // 固定 Demo 知识包在启动时允许降级，但错误必须完整记录，便于修正资料后重试。
            log.error("Service manual import failed", exception);
        }
    }

    private Optional<Path> findSupportedManual(Path sourceDirectory) throws Exception {
        try (var files = Files.list(sourceDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(parser::supports)
                    .sorted()
                    .findFirst();
        }
    }

    private void importManual(Path path, ParsedManual manual) {
        try {
            long knowledgeBaseId = ensureKnowledgeBase();
            long batchId = createBatch(knowledgeBaseId);
            String fileSha = sha256(Files.readAllBytes(path));
            long sourceFileId = registerSourceFile(
                    knowledgeBaseId,
                    batchId,
                    path,
                    fileSha);
            long problemTypeId = jdbcTemplate.queryForObject(
                    "SELECT id FROM problem_type WHERE code = ?",
                    Long.class,
                    manual.problemTypeCode());

            Map<Integer, Long> sourcePages = new LinkedHashMap<>();
            for (ManualKnowledge unit : manual.units()) {
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

            jdbcTemplate.update(
                    "UPDATE source_file SET status = 'VALIDATED' WHERE id = ?",
                    sourceFileId);
            jdbcTemplate.update("""
                    UPDATE ingestion_batch
                    SET status = 'COMPLETED', total_records = ?,
                        completed_at = CURRENT_TIMESTAMP(3)
                    WHERE id = ?
                    """, manual.units().size(), batchId);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish service manual knowledge", exception);
        }
    }

    private long ensureKnowledgeBase() {
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (code, name, status)
                VALUES (?, 'AI Repair Assistant Demo Knowledge', 'ACTIVE')
                ON DUPLICATE KEY UPDATE status = 'ACTIVE'
                """, KNOWLEDGE_BASE_CODE);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_base WHERE code = ?",
                Long.class,
                KNOWLEDGE_BASE_CODE);
    }

    private long createBatch(long knowledgeBaseId) {
        String batchKey = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO ingestion_batch (
                    knowledge_base_id, batch_key, status,
                    total_files, started_at
                ) VALUES (?, ?, 'PROCESSING', 1, CURRENT_TIMESTAMP(3))
                """, knowledgeBaseId, batchKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_batch WHERE batch_key = ?",
                Long.class,
                batchKey);
    }

    private long registerSourceFile(
            long knowledgeBaseId,
            long batchId,
            Path path,
            String fileSha) throws Exception {
        jdbcTemplate.update("""
                INSERT INTO source_file (
                    knowledge_base_id, ingestion_batch_id,
                    logical_document_key, original_file_name,
                    file_type, source_kind, language_code,
                    sha256, file_size_bytes, parser_version, status
                ) VALUES (?, ?, 'SERVICE_MANUAL:RIR1-SSB', ?,
                          'PDF', 'SERVICE_MANUAL', 'en-US',
                          ?, ?, ?, 'PARSED')
                ON DUPLICATE KEY UPDATE
                    ingestion_batch_id = VALUES(ingestion_batch_id),
                    parser_version = VALUES(parser_version),
                    status = 'PARSED'
                """,
                knowledgeBaseId,
                batchId,
                path.getFileName().toString(),
                fileSha,
                Files.size(path),
                PARSER_VERSION);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM source_file
                WHERE knowledge_base_id = ? AND sha256 = ?
                """, Long.class, knowledgeBaseId, fileSha);
    }

    private long insertSourcePage(
            long sourceFileId,
            ParsedManual manual,
            ManualKnowledge unit) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("documentName", manual.documentName());
        locator.put("pdfPageIndex", unit.sourcePage().pdfPageIndex());
        locator.put("printedPageLabel", unit.sourcePage().printedPageLabel());
        locator.put("sectionPath", unit.sectionPath());
        locator.put("rawText", unit.sourcePage().text());
        String rawJson = writeJson(locator);
        String businessKey = "RIR1-SSB:SERVICE:PDF_PAGE:" + unit.sourcePage().pdfPageIndex();
        jdbcTemplate.update("""
                INSERT INTO source_record (
                    source_file_id, record_type, business_key,
                    sheet_name, source_row_no, source_cell_range,
                    raw_payload, record_fingerprint, validation_status
                ) VALUES (?, 'MANUAL_PAGE', ?, 'PDF', ?, ?,
                          CAST(? AS JSON), ?, 'VALID')
                ON DUPLICATE KEY UPDATE
                    raw_payload = VALUES(raw_payload),
                    record_fingerprint = VALUES(record_fingerprint),
                    validation_status = 'VALID'
                """,
                sourceFileId,
                businessKey,
                unit.sourcePage().pdfPageIndex(),
                "PAGE:" + unit.sourcePage().pdfPageIndex(),
                rawJson,
                sha256(rawJson.getBytes(StandardCharsets.UTF_8)));
        return jdbcTemplate.queryForObject("""
                SELECT id FROM source_record
                WHERE source_file_id = ? AND record_type = 'MANUAL_PAGE'
                  AND business_key = ?
                """, Long.class, sourceFileId, businessKey);
    }

    private void publishKnowledgeUnit(
            long knowledgeBaseId,
            long problemTypeId,
            long sourceRecordId,
            ParsedManual manual,
            ManualKnowledge unit,
            String fileSha) {
        String pointId = UUID.nameUUIDFromBytes(
                ("manual:" + unit.unitKey()).getBytes(StandardCharsets.UTF_8))
                .toString();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("manufacturer", manual.manufacturer());
        content.put("model", manual.model());
        content.put("problemTypeCode", manual.problemTypeCode());
        content.put("errorCode", "E4");
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

        jdbcTemplate.update("""
                INSERT INTO knowledge_unit (
                    knowledge_base_id, unit_key, unit_type, current_version_no
                ) VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE current_version_no = 1
                """, knowledgeBaseId, unit.unitKey(), unit.unitType());
        long unitId = jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_unit
                WHERE knowledge_base_id = ? AND unit_key = ?
                """, Long.class, knowledgeBaseId, unit.unitKey());

        jdbcTemplate.update("""
                INSERT INTO knowledge_unit_version (
                    knowledge_unit_id, version_no, schema_version,
                    title, language_code, trust_level, status,
                    content_json, source_fingerprint, content_fingerprint,
                    qdrant_point_id, published_at
                ) VALUES (?, 1, 'SERVICE_MANUAL_V1', ?, 'ja-JP',
                          'AUTHORITATIVE', 'PUBLISHED', CAST(? AS JSON),
                          ?, ?, ?, CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title),
                    content_json = VALUES(content_json),
                    source_fingerprint = VALUES(source_fingerprint),
                    content_fingerprint = VALUES(content_fingerprint),
                    status = 'PUBLISHED'
                """,
                unitId,
                unit.titleJa(),
                contentJson,
                sourceFingerprint,
                contentFingerprint,
                pointId);
        long versionId = jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_unit_version
                WHERE knowledge_unit_id = ? AND version_no = 1
                """, Long.class, unitId);

        jdbcTemplate.update("""
                INSERT IGNORE INTO knowledge_unit_problem_type (
                    knowledge_unit_version_id, problem_type_id, rank_no
                ) VALUES (?, ?, 1)
                """, versionId, problemTypeId);
        jdbcTemplate.update("""
                INSERT INTO knowledge_unit_source (
                    knowledge_unit_version_id, source_record_id, relation_type
                ) VALUES (?, ?, 'PRIMARY')
                ON DUPLICATE KEY UPDATE relation_type = 'PRIMARY'
                """, versionId, sourceRecordId);
        // 语义索引使用日文业务解释；英文原文仍通过 source_quote 独立保留。
        upsertProjection(versionId, "PROBLEM", unit.problemProjectionJa());
        upsertProjection(versionId, "RESOLUTION", unit.resolutionProjectionJa());

        String sourceReference = "%s · PDF P%d · 手册 P%s · §%s".formatted(
                manual.documentName(),
                unit.sourcePage().pdfPageIndex(),
                Optional.ofNullable(unit.sourcePage().printedPageLabel()).orElse("-"),
                unit.sectionPath());
        jdbcTemplate.update("""
                INSERT INTO manual_knowledge_projection_v1 (
                    knowledge_unit_version_id, document_name, document_kind,
                    manufacturer, model, problem_type_code, knowledge_type,
                    error_code, title, title_ja, summary, summary_ja,
                    source_quote, source_anchor, source_region_json,
                    action_steps_json, action_steps_ja_json,
                    safety_warnings_json, safety_warnings_ja_json,
                    candidate_codes_json,
                    source_reference, pdf_page_index, printed_page_label,
                    section_path, problem_projection, problem_projection_ja,
                    resolution_projection, resolution_projection_ja,
                    qdrant_point_id, trust_level
                ) VALUES (?, ?, 'SERVICE_MANUAL', ?, ?, ?, ?, 'E4', ?, ?, ?, ?, ?, ?,
                          CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON),
                          CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'AUTHORITATIVE')
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title), title_ja = VALUES(title_ja),
                    summary = VALUES(summary), summary_ja = VALUES(summary_ja),
                    source_quote = VALUES(source_quote),
                    source_anchor = VALUES(source_anchor),
                    source_region_json = VALUES(source_region_json),
                    action_steps_json = VALUES(action_steps_json),
                    action_steps_ja_json = VALUES(action_steps_ja_json),
                    safety_warnings_json = VALUES(safety_warnings_json),
                    safety_warnings_ja_json = VALUES(safety_warnings_ja_json),
                    candidate_codes_json = VALUES(candidate_codes_json),
                    source_reference = VALUES(source_reference),
                    problem_projection = VALUES(problem_projection),
                    problem_projection_ja = VALUES(problem_projection_ja),
                    resolution_projection = VALUES(resolution_projection),
                    resolution_projection_ja = VALUES(resolution_projection_ja),
                    indexed = FALSE
                """,
                versionId,
                manual.documentName(),
                manual.manufacturer(),
                manual.model(),
                manual.problemTypeCode(),
                unit.unitType(),
                unit.title(),
                unit.titleJa(),
                unit.summary(),
                unit.summaryJa(),
                unit.sourceQuote(),
                unit.sourceAnchor(),
                writeJson(unit.sourceRegion()),
                writeJson(unit.actionSteps()),
                writeJson(unit.actionStepsJa()),
                writeJson(unit.safetyWarnings()),
                writeJson(unit.safetyWarningsJa()),
                writeJson(unit.candidateCodes()),
                sourceReference,
                unit.sourcePage().pdfPageIndex(),
                unit.sourcePage().printedPageLabel(),
                unit.sectionPath(),
                unit.problemProjection(),
                unit.problemProjectionJa(),
                unit.resolutionProjection(),
                unit.resolutionProjectionJa(),
                pointId);
    }

    private void upsertProjection(long versionId, String type, String text) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_search_projection (
                    knowledge_unit_version_id, projection_type,
                    projection_text, projection_hash
                ) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    projection_text = VALUES(projection_text),
                    projection_hash = VALUES(projection_hash)
                """,
                versionId,
                type,
                text,
                sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void indexPendingManualKnowledge() {
        if (!openAiGateway.enabled()) {
            log.info("OpenAI key is not configured; manual semantic indexing is deferred");
            return;
        }
        List<PendingManualVector> pending = jdbcTemplate.query("""
                SELECT id, model, problem_type_code, error_code,
                       knowledge_type, problem_projection, qdrant_point_id
                FROM manual_knowledge_projection_v1
                WHERE indexed = FALSE
                ORDER BY id
                """, (resultSet, rowNum) -> new PendingManualVector(
                resultSet.getLong("id"),
                resultSet.getString("model"),
                resultSet.getString("problem_type_code"),
                resultSet.getString("error_code"),
                resultSet.getString("knowledge_type"),
                resultSet.getString("problem_projection"),
                resultSet.getString("qdrant_point_id")));
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
            String placeholders = batch.stream().map(item -> "?")
                    .reduce((left, right) -> left + "," + right)
                    .orElse("?");
            jdbcTemplate.update(
                    "UPDATE manual_knowledge_projection_v1 SET indexed = TRUE "
                            + "WHERE id IN (" + placeholders + ")",
                    batch.stream().map(PendingManualVector::id).toArray());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize manual knowledge", exception);
        }
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
}
