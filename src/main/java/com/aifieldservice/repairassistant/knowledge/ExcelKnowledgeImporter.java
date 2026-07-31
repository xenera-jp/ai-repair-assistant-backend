package com.aifieldservice.repairassistant.knowledge;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.integration.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.QdrantGateway;
import com.aifieldservice.repairassistant.integration.QdrantGateway.VectorPoint;
import com.aifieldservice.repairassistant.knowledge.ProblemCatalogService.ProblemMatch;

import tools.jackson.databind.ObjectMapper;

@Component
public class ExcelKnowledgeImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExcelKnowledgeImporter.class);
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("(?i)(?<![A-Z0-9])E\\d+(?![A-Z0-9])");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Map<SourceKind, Set<String>> HEADER_FINGERPRINTS = Map.of(
            SourceKind.CALL_HISTORY,
            Set.of("受付ID", "受付日時", "申告内容(応対記録・聞き取り)", "機種", "製造番号"),
            SourceKind.REPAIR_HISTORY,
            Set.of("作業ID", "受付ID", "訪問日", "症状(故障モード)", "処置"),
            SourceKind.PART_USAGE_HISTORY,
            Set.of("明細ID", "作業ID", "受付ID", "部品番号", "数量"));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RepairAssistantProperties properties;
    private final ProblemCatalogService problemCatalog;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;

    public ExcelKnowledgeImporter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RepairAssistantProperties properties,
            ProblemCatalogService problemCatalog,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.problemCatalog = problemCatalog;
        this.openAiGateway = openAiGateway;
        this.qdrantGateway = qdrantGateway;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.knowledge().importEnabled()) {
            return;
        }
        Path sourcePath = Path.of(properties.knowledge().sourcePath()).toAbsolutePath();
        if (!Files.isDirectory(sourcePath)) {
            log.warn("Knowledge source directory does not exist: {}", sourcePath);
            return;
        }
        try {
            int projectionCount = Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM repair_case_projection_v1",
                    Integer.class)).orElse(0);
            if (projectionCount == 0) {
                ImportResult result = importExcelKnowledge(sourcePath);
                log.info(
                        "Knowledge import completed: {} files, {} source rows, {} repair cases",
                        result.fileCount(),
                        result.sourceRecordCount(),
                        result.caseCount());
            }
            indexPendingCases();
        } catch (Exception exception) {
            log.error("Knowledge import failed", exception);
        }
    }

    @Transactional
    public ImportResult importExcelKnowledge(Path sourcePath) throws Exception {
        List<ParsedFile> files = new ArrayList<>();
        try (var paths = Files.list(sourcePath)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".xlsx"))
                    .sorted()
                    .toList()) {
                files.add(parse(path));
            }
        }

        Map<SourceKind, ParsedFile> byKind = new HashMap<>();
        for (ParsedFile file : files) {
            byKind.put(file.kind(), file);
        }
        for (SourceKind kind : SourceKind.values()) {
            if (!byKind.containsKey(kind)) {
                throw new IllegalStateException("Missing Excel source: " + kind);
            }
        }

        long knowledgeBaseId = ensureKnowledgeBase();
        long batchId = createBatch(knowledgeBaseId, files);
        Map<SourceKind, Long> sourceFileIds = new HashMap<>();
        int totalRows = 0;
        for (ParsedFile file : files) {
            long sourceFileId = registerSourceFile(knowledgeBaseId, batchId, file);
            sourceFileIds.put(file.kind(), sourceFileId);
            importSourceRows(sourceFileId, file);
            totalRows += file.rows().size();
        }

        ParsedFile callsFile = byKind.get(SourceKind.CALL_HISTORY);
        ParsedFile repairsFile = byKind.get(SourceKind.REPAIR_HISTORY);
        ParsedFile partsFile = byKind.get(SourceKind.PART_USAGE_HISTORY);

        Map<String, Map<String, String>> calls = indexBy(callsFile.rows(), "受付ID");
        Map<String, List<Map<String, String>>> repairs = groupBy(
                repairsFile.rows(),
                "受付ID");
        Map<String, List<Map<String, String>>> parts = groupBy(
                partsFile.rows(),
                "作業ID");

        int caseCount = 0;
        for (Map.Entry<String, List<Map<String, String>>> entry : repairs.entrySet()) {
            Map<String, String> call = calls.get(entry.getKey());
            if (call == null) {
                continue;
            }
            List<Map<String, String>> visits = entry.getValue().stream()
                    .sorted(Comparator
                            .comparing((Map<String, String> value) -> parseDate(
                                    value.get("訪問日")))
                            .thenComparing(value -> value.getOrDefault("作業ID", "")))
                    .toList();
            List<Map<String, String>> usedParts = visits.stream()
                    .flatMap(visit -> parts
                            .getOrDefault(visit.getOrDefault("作業ID", ""), List.of())
                            .stream())
                    .toList();
            buildRepairCase(
                    knowledgeBaseId,
                    sourceFileIds,
                    callsFile,
                    repairsFile,
                    partsFile,
                    call,
                    visits,
                    usedParts);
            caseCount++;
        }

        jdbcTemplate.update("""
                UPDATE source_file
                SET status = 'VALIDATED'
                WHERE ingestion_batch_id = ?
                """, batchId);
        jdbcTemplate.update("""
                UPDATE ingestion_batch
                SET status = 'COMPLETED',
                    total_records = ?,
                    completed_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, totalRows, batchId);

        return new ImportResult(files.size(), totalRows, caseCount);
    }

    private ParsedFile parse(Path path) throws Exception {
        DataFormatter formatter = new DataFormatter(Locale.JAPAN);
        try (InputStream input = Files.newInputStream(path);
                Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            HeaderMatch header = findHeader(sheet, formatter)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown Excel schema: " + path.getFileName()));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int rowIndex = header.rowIndex() + 1;
                    rowIndex <= sheet.getLastRowNum();
                    rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean hasValue = false;
                for (Map.Entry<Integer, String> column : header.headers().entrySet()) {
                    String value = formatter
                            .formatCellValue(row.getCell(column.getKey()))
                            .strip();
                    values.put(column.getValue(), value);
                    hasValue = hasValue || !value.isBlank();
                }
                if (hasValue) {
                    values.put("_sourceRow", String.valueOf(rowIndex + 1));
                    rows.add(values);
                }
            }
            return new ParsedFile(
                    path,
                    header.kind(),
                    sheet.getSheetName(),
                    header.rowIndex() + 1,
                    rows,
                    sha256(Files.readAllBytes(path)));
        }
    }

    private Optional<HeaderMatch> findHeader(Sheet sheet, DataFormatter formatter) {
        for (int rowIndex = 0; rowIndex <= Math.min(9, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<Integer, String> headers = new LinkedHashMap<>();
            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell).strip();
                if (!value.isBlank()) {
                    headers.put(cell.getColumnIndex(), value);
                }
            }
            Set<String> values = new LinkedHashSet<>(headers.values());
            for (Map.Entry<SourceKind, Set<String>> fingerprint
                    : HEADER_FINGERPRINTS.entrySet()) {
                if (values.containsAll(fingerprint.getValue())) {
                    return Optional.of(new HeaderMatch(
                            fingerprint.getKey(),
                            rowIndex,
                            headers));
                }
            }
        }
        return Optional.empty();
    }

    private long ensureKnowledgeBase() {
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (code, name, status)
                VALUES ('AI_REPAIR_DEMO', 'AI Repair Assistant Demo Knowledge', 'ACTIVE')
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    status = 'ACTIVE'
                """);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_base WHERE code = 'AI_REPAIR_DEMO'",
                Long.class);
    }

    private long createBatch(long knowledgeBaseId, List<ParsedFile> files) {
        String batchKey = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO ingestion_batch (
                    knowledge_base_id, batch_key, status,
                    total_files, started_at
                ) VALUES (?, ?, 'PROCESSING', ?, CURRENT_TIMESTAMP(3))
                """, knowledgeBaseId, batchKey, files.size());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_batch WHERE batch_key = ?",
                Long.class,
                batchKey);
    }

    private long registerSourceFile(
            long knowledgeBaseId,
            long batchId,
            ParsedFile file) throws Exception {
        jdbcTemplate.update("""
                INSERT INTO source_file (
                    knowledge_base_id, ingestion_batch_id,
                    logical_document_key, original_file_name,
                    file_type, source_kind, language_code,
                    sha256, file_size_bytes, parser_version, status
                ) VALUES (?, ?, ?, ?, 'XLSX', ?, 'ja-JP', ?, ?, 'POI_V1', 'PARSED')
                ON DUPLICATE KEY UPDATE
                    status = 'PARSED',
                    parser_version = 'POI_V1'
                """,
                knowledgeBaseId,
                batchId,
                file.kind().name(),
                file.path().getFileName().toString(),
                file.kind().name(),
                file.sha256(),
                Files.size(file.path()));
        return jdbcTemplate.queryForObject("""
                SELECT id FROM source_file
                WHERE knowledge_base_id = ? AND sha256 = ?
                """, Long.class, knowledgeBaseId, file.sha256());
    }

    private void importSourceRows(long sourceFileId, ParsedFile file) {
        for (Map<String, String> row : file.rows()) {
            String businessKey = businessKey(file.kind(), row);
            String rawJson = writeJson(row);
            jdbcTemplate.update("""
                    INSERT IGNORE INTO source_record (
                        source_file_id, record_type, business_key,
                        sheet_name, source_row_no, raw_payload,
                        record_fingerprint, validation_status
                    ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, 'VALID')
                    """,
                    sourceFileId,
                    file.kind().recordType,
                    businessKey,
                    file.sheetName(),
                    integer(row.get("_sourceRow")),
                    rawJson,
                    sha256(rawJson.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void buildRepairCase(
            long knowledgeBaseId,
            Map<SourceKind, Long> sourceFileIds,
            ParsedFile callsFile,
            ParsedFile repairsFile,
            ParsedFile partsFile,
            Map<String, String> call,
            List<Map<String, String>> visits,
            List<Map<String, String>> parts) {
        Map<String, String> finalVisit = visits.get(visits.size() - 1);
        String receptionId = call.get("受付ID");
        String model = firstNonBlank(
                finalVisit.get("機種"),
                call.get("機種"),
                "UNKNOWN");
        String serial = firstNonBlank(
                finalVisit.get("製造番号"),
                call.get("製造番号"),
                "UNKNOWN-" + receptionId);
        String complaint = call.getOrDefault("申告内容(応対記録・聞き取り)", "");
        String problemText = visits.stream()
                .map(visit -> joinNonBlank(
                        visit.get("症状(故障モード)"),
                        visit.get("現象(自由文)")))
                .distinct()
                .reduce("", this::joinNonBlank);
        String errorCode = extractErrorCodes(visits, call).stream()
                .findFirst()
                .orElse("");
        ProblemMatch problemMatch = problemCatalog
                .match(model, errorCode, problemText + " " + complaint)
                .orElseThrow(() -> new IllegalStateException(
                        "No problem type mapping for " + receptionId));
        String problemTypeCode = problemMatch.definition().code();
        String problemTypeLabel = problemMatch.definition().nameZh();
        String causeText = visits.stream()
                .map(visit -> visit.getOrDefault("原因", ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce("", this::joinNonBlank);
        String actionText = visits.stream()
                .map(visit -> visit.getOrDefault("処置", ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce("", this::joinNonBlank);
        String onsiteObservation = visits.stream()
                .map(visit -> visit.getOrDefault("現象(自由文)", ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce("", this::joinNonBlank);
        boolean firstFix = solved(visits.get(0).get("初回解決"));
        boolean finalResolved = solved(finalVisit.get("初回解決"));
        int totalDuration = visits.stream()
                .mapToInt(visit -> integer(visit.get("作業時間(分)")))
                .sum();

        List<Map<String, Object>> normalizedParts = parts.stream()
                .map(part -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("partNumber", part.getOrDefault("部品番号", ""));
                    value.put("name", part.getOrDefault("部品名称", ""));
                    value.put("quantity", decimal(part.get("数量")));
                    value.put("workId", part.getOrDefault("作業ID", ""));
                    return value;
                })
                .toList();
        List<String> errorCodes = extractErrorCodes(visits, call);

        String problemProjection = """
                型号: %s
                错误码: %s
                问题分类: %s
                客户申告: %s
                现场现象: %s
                """.formatted(
                        model,
                        String.join(", ", errorCodes),
                        problemTypeLabel,
                        complaint,
                        onsiteObservation);
        String resolutionProjection = """
                型号: %s
                问题分类: %s
                原因记录: %s
                维修处置: %s
                使用部件: %s
                最终解决: %s
                """.formatted(
                        model,
                        problemTypeLabel,
                        causeText,
                        actionText,
                        normalizedParts,
                        finalResolved ? "是" : "否");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("receptionId", receptionId);
        content.put("model", model);
        content.put("serialNumber", serial);
        content.put("customerComplaint", complaint);
        content.put("problemTypeCode", problemTypeCode);
        content.put("errorCodes", errorCodes);
        content.put("visits", visits);
        content.put("parts", normalizedParts);
        content.put("firstFix", firstFix);
        content.put("finalResolved", finalResolved);

        String sourceFingerprint = sha256(
                writeJson(Map.of(
                        "call", call,
                        "visits", visits,
                        "parts", parts))
                        .getBytes(StandardCharsets.UTF_8));
        String contentJson = writeJson(content);
        String contentFingerprint = sha256(contentJson.getBytes(StandardCharsets.UTF_8));
        String pointId = UUID.nameUUIDFromBytes(
                ("repair-case:" + receptionId).getBytes(StandardCharsets.UTF_8))
                .toString();
        String unitKey = "REPAIR_CASE:" + receptionId;
        String title = "%s %s 维修案例 %s".formatted(
                model,
                problemTypeLabel,
                receptionId);
        String trustLevel = finalResolved ? "VERIFIED_CASE" : "OBSERVED_CASE";

        jdbcTemplate.update("""
                INSERT INTO knowledge_unit (
                    knowledge_base_id, unit_key, unit_type, current_version_no
                ) VALUES (?, ?, 'REPAIR_CASE', 1)
                ON DUPLICATE KEY UPDATE current_version_no = 1
                """, knowledgeBaseId, unitKey);
        long unitId = jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_unit
                WHERE knowledge_base_id = ? AND unit_key = ?
                """, Long.class, knowledgeBaseId, unitKey);
        jdbcTemplate.update("""
                INSERT IGNORE INTO knowledge_unit_version (
                    knowledge_unit_id, version_no, schema_version,
                    title, language_code, trust_level, status,
                    content_json, source_fingerprint, content_fingerprint,
                    qdrant_point_id, published_at
                ) VALUES (
                    ?, 1, 'REPAIR_CASE_V1', ?, 'ja-JP', ?, 'PUBLISHED',
                    CAST(? AS JSON), ?, ?, ?, CURRENT_TIMESTAMP(3)
                )
                """,
                unitId,
                title,
                trustLevel,
                contentJson,
                sourceFingerprint,
                contentFingerprint,
                pointId);
        long unitVersionId = jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_unit_version
                WHERE knowledge_unit_id = ? AND version_no = 1
                """, Long.class, unitId);

        jdbcTemplate.update("""
                INSERT IGNORE INTO knowledge_unit_problem_type (
                    knowledge_unit_version_id, problem_type_id, rank_no
                ) VALUES (?, ?, 1)
                """, unitVersionId, problemMatch.definition().id());
        insertProjection(unitVersionId, "PROBLEM", problemProjection);
        insertProjection(unitVersionId, "RESOLUTION", resolutionProjection);

        String sourceReference = "%s#%s; %s#%s".formatted(
                callsFile.path().getFileName(),
                call.get("_sourceRow"),
                repairsFile.path().getFileName(),
                visits.stream()
                        .map(visit -> visit.get("_sourceRow"))
                        .reduce((left, right) -> left + "," + right)
                        .orElse(""));
        jdbcTemplate.update("""
                INSERT INTO repair_case_projection_v1 (
                    knowledge_unit_version_id, reception_id, model,
                    serial_number, customer_site_name, received_at,
                    problem_type_code, problem_type_label,
                    error_codes_json, complaint, onsite_observation,
                    cause_text, action_text, final_resolved, first_fix,
                    visit_count, total_duration_minutes, parts_json,
                    problem_projection, resolution_projection,
                    source_reference, qdrant_point_id, trust_level
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?,
                    ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?
                )
                ON DUPLICATE KEY UPDATE
                    problem_type_code = VALUES(problem_type_code),
                    problem_type_label = VALUES(problem_type_label),
                    error_codes_json = VALUES(error_codes_json),
                    complaint = VALUES(complaint),
                    onsite_observation = VALUES(onsite_observation),
                    cause_text = VALUES(cause_text),
                    action_text = VALUES(action_text),
                    final_resolved = VALUES(final_resolved),
                    first_fix = VALUES(first_fix),
                    visit_count = VALUES(visit_count),
                    total_duration_minutes = VALUES(total_duration_minutes),
                    parts_json = VALUES(parts_json),
                    problem_projection = VALUES(problem_projection),
                    resolution_projection = VALUES(resolution_projection),
                    source_reference = VALUES(source_reference),
                    trust_level = VALUES(trust_level)
                """,
                unitVersionId,
                receptionId,
                model,
                serial,
                call.getOrDefault("顧客/店舗名", ""),
                parseDateTime(call.get("受付日時")),
                problemTypeCode,
                problemTypeLabel,
                writeJson(errorCodes),
                complaint,
                onsiteObservation,
                causeText,
                actionText,
                finalResolved,
                firstFix,
                visits.size(),
                totalDuration,
                writeJson(normalizedParts),
                problemProjection,
                resolutionProjection,
                sourceReference,
                pointId,
                trustLevel);

        linkSource(unitVersionId, sourceFileIds.get(SourceKind.CALL_HISTORY),
                SourceKind.CALL_HISTORY.recordType, receptionId, "PRIMARY");
        for (Map<String, String> visit : visits) {
            linkSource(unitVersionId, sourceFileIds.get(SourceKind.REPAIR_HISTORY),
                    SourceKind.REPAIR_HISTORY.recordType, visit.get("作業ID"), "SUPPORTING");
        }
        for (Map<String, String> part : parts) {
            linkSource(unitVersionId, sourceFileIds.get(SourceKind.PART_USAGE_HISTORY),
                    SourceKind.PART_USAGE_HISTORY.recordType, part.get("明細ID"), "SUPPORTING");
        }
    }

    private void insertProjection(long unitVersionId, String type, String text) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO knowledge_search_projection (
                    knowledge_unit_version_id, projection_type,
                    projection_text, projection_hash
                ) VALUES (?, ?, ?, ?)
                """,
                unitVersionId,
                type,
                text,
                sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void linkSource(
            long unitVersionId,
            long sourceFileId,
            String recordType,
            String businessKey,
            String relationType) {
        List<Long> sourceIds = jdbcTemplate.query("""
                SELECT id FROM source_record
                WHERE source_file_id = ?
                  AND record_type = ?
                  AND business_key = ?
                """, (resultSet, rowNum) -> resultSet.getLong(1),
                sourceFileId, recordType, businessKey);
        if (!sourceIds.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO knowledge_unit_source (
                        knowledge_unit_version_id, source_record_id, relation_type
                    ) VALUES (?, ?, ?)
                    """, unitVersionId, sourceIds.get(0), relationType);
        }
    }

    private void indexPendingCases() {
        if (!openAiGateway.enabled()) {
            log.info("OpenAI key is not configured; semantic indexing is deferred");
            return;
        }
        List<PendingVector> pending = jdbcTemplate.query("""
                SELECT id, reception_id, model, problem_type_code,
                       problem_projection, qdrant_point_id
                FROM repair_case_projection_v1
                WHERE indexed = FALSE
                ORDER BY id
                """, (resultSet, rowNum) -> new PendingVector(
                resultSet.getLong("id"),
                resultSet.getString("reception_id"),
                resultSet.getString("model"),
                resultSet.getString("problem_type_code"),
                resultSet.getString("problem_projection"),
                resultSet.getString("qdrant_point_id")));

        for (int offset = 0; offset < pending.size(); offset += 64) {
            List<PendingVector> batch = pending.subList(
                    offset,
                    Math.min(offset + 64, pending.size()));
            List<float[]> embeddings = openAiGateway.embed(
                    batch.stream().map(PendingVector::projection).toList());
            if (embeddings.size() != batch.size()) {
                return;
            }
            List<VectorPoint> points = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                PendingVector item = batch.get(index);
                points.add(new VectorPoint(
                        item.pointId(),
                        embeddings.get(index),
                        Map.of(
                                "caseId", item.id(),
                                "receptionId", item.receptionId(),
                                "model", item.model(),
                                "problemTypeCode", item.problemTypeCode())));
            }
            if (!qdrantGateway.upsert(points)) {
                return;
            }
            jdbcTemplate.update("""
                    UPDATE repair_case_projection_v1
                    SET indexed = TRUE
                    WHERE id IN (%s)
                    """.formatted(batch.stream()
                    .map(item -> "?")
                    .reduce((left, right) -> left + "," + right)
                    .orElse("?")),
                    batch.stream().map(PendingVector::id).toArray());
        }
    }

    private Map<String, Map<String, String>> indexBy(
            List<Map<String, String>> rows,
            String key) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            result.put(row.get(key), row);
        }
        return result;
    }

    private Map<String, List<Map<String, String>>> groupBy(
            List<Map<String, String>> rows,
            String key) {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            result.computeIfAbsent(row.get(key), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private List<String> extractErrorCodes(
            List<Map<String, String>> visits,
            Map<String, String> call) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        visits.forEach(visit -> addErrorCodes(codes, visit.get("エラーコード")));
        addErrorCodes(codes, call.get("エラーコード"));
        addErrorCodes(codes, call.get("申告内容(応対記録・聞き取り)"));
        return List.copyOf(codes);
    }

    private void addErrorCodes(Set<String> codes, String value) {
        Matcher matcher = ERROR_CODE_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            codes.add(matcher.group().toUpperCase(Locale.ROOT));
        }
    }

    private String businessKey(SourceKind kind, Map<String, String> row) {
        return switch (kind) {
            case CALL_HISTORY -> row.get("受付ID");
            case REPAIR_HISTORY -> row.get("作業ID");
            case PART_USAGE_HISTORY -> row.get("明細ID");
        };
    }

    private boolean solved(String value) {
        return "○".equals(value)
                || "〇".equals(value)
                || "YES".equalsIgnoreCase(value)
                || "TRUE".equalsIgnoreCase(value);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            return LocalDate.of(1970, 1, 1);
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            try {
                return parseDate(value).atStartOfDay();
            } catch (Exception ignored) {
                return LocalDateTime.ofInstant(
                        java.time.Instant.EPOCH,
                        ZoneId.systemDefault());
            }
        }
    }

    private int integer(String value) {
        try {
            return new BigDecimal(value.replace(",", "")).intValue();
        } catch (Exception exception) {
            return 0;
        }
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (Exception exception) {
            return BigDecimal.ZERO;
        }
    }

    private String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "；" + right;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create SHA-256", exception);
        }
    }

    enum SourceKind {
        CALL_HISTORY("CALL_HISTORY_ROW"),
        REPAIR_HISTORY("REPAIR_HISTORY_ROW"),
        PART_USAGE_HISTORY("PART_USAGE_HISTORY_ROW");

        private final String recordType;

        SourceKind(String recordType) {
            this.recordType = recordType;
        }
    }

    record ParsedFile(
            Path path,
            SourceKind kind,
            String sheetName,
            int headerRow,
            List<Map<String, String>> rows,
            String sha256) {
    }

    record HeaderMatch(
            SourceKind kind,
            int rowIndex,
            Map<Integer, String> headers) {
    }

    record PendingVector(
            long id,
            String receptionId,
            String model,
            String problemTypeCode,
            String projection,
            String pointId) {
    }

    record ImportResult(int fileCount, int sourceRecordCount, int caseCount) {
    }
}
