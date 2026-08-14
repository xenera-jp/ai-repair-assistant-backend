package com.aifieldservice.repairassistant.service.knowledge;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.integration.openai.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.qdrant.QdrantGateway;
import com.aifieldservice.repairassistant.integration.qdrant.QdrantGateway.VectorPoint;
import com.aifieldservice.repairassistant.dao.knowledge.IngestionBatchMapper;
import com.aifieldservice.repairassistant.dao.knowledge.KnowledgeBaseMapper;
import com.aifieldservice.repairassistant.dao.knowledge.KnowledgeUnitMapper;
import com.aifieldservice.repairassistant.dao.knowledge.KnowledgeUnitRelationMapper;
import com.aifieldservice.repairassistant.dao.knowledge.RepairCaseImportMapper;
import com.aifieldservice.repairassistant.domain.knowledge.command.RepairCaseProjectionCommand;
import com.aifieldservice.repairassistant.dao.knowledge.SourceFileMapper;
import com.aifieldservice.repairassistant.domain.knowledge.command.SourceFileCommand;
import com.aifieldservice.repairassistant.dao.knowledge.SourceRecordMapper;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemMatch;

import tools.jackson.databind.ObjectMapper;

/**
 * 固定 Excel 知识包的构建流水线。
 *
 * <p>输入不是三个互不相关的表，而是一组可通过业务键拼接的事件数据：
 * 客服受理（受付ID） -> 一次或多次维修到访（作業ID） -> 每次使用的部件（明細ID）。
 * Importer 同时保留原始行、构建标准维修案例、生成检索投影并写入 Qdrant，
 * 从而兼顾可追溯性和在线查询效率。
 *
 * <p>该类实现 {@link ApplicationRunner}，适合当前“启动时加载固定 Demo 知识库”的范围。
 * 正式环境应把导入和索引拆成独立批处理任务，并补充批次重试、审核和发布状态机。
 */
@Component
public class ExcelKnowledgeImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExcelKnowledgeImporter.class);
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("(?i)(?<![A-Z0-9])E\\d+(?![A-Z0-9])");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 通过业务列组合识别文件类型，而不是依赖容易变化的文件名。
     * 只要关键列仍存在，销售或客户重命名文件也不会影响导入。
     */
    private static final Map<SourceKind, Set<String>> HEADER_FINGERPRINTS = Map.of(
            SourceKind.CALL_HISTORY,
            Set.of("受付ID", "受付日時", "申告内容(応対記録・聞き取り)", "機種", "製造番号"),
            SourceKind.REPAIR_HISTORY,
            Set.of("作業ID", "受付ID", "訪問日", "症状(故障モード)", "処置"),
            SourceKind.PART_USAGE_HISTORY,
            Set.of("明細ID", "作業ID", "受付ID", "部品番号", "数量"));

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IngestionBatchMapper ingestionBatchMapper;
    private final SourceFileMapper sourceFileMapper;
    private final SourceRecordMapper sourceRecordMapper;
    private final KnowledgeUnitMapper knowledgeUnitMapper;
    private final KnowledgeUnitRelationMapper knowledgeUnitRelationMapper;
    private final RepairCaseImportMapper repairCaseImportMapper;
    private final ObjectMapper objectMapper;
    private final RepairAssistantProperties properties;
    private final ProblemCatalogService problemCatalog;
    private final OpenAiGateway openAiGateway;
    private final QdrantGateway qdrantGateway;
    private final ObjectProvider<ExcelKnowledgeImporter> self;

    public ExcelKnowledgeImporter(
            KnowledgeBaseMapper knowledgeBaseMapper,
            IngestionBatchMapper ingestionBatchMapper,
            SourceFileMapper sourceFileMapper,
            SourceRecordMapper sourceRecordMapper,
            KnowledgeUnitMapper knowledgeUnitMapper,
            KnowledgeUnitRelationMapper knowledgeUnitRelationMapper,
            RepairCaseImportMapper repairCaseImportMapper,
            ObjectMapper objectMapper,
            RepairAssistantProperties properties,
            ProblemCatalogService problemCatalog,
            OpenAiGateway openAiGateway,
            QdrantGateway qdrantGateway,
            ObjectProvider<ExcelKnowledgeImporter> self) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.ingestionBatchMapper = ingestionBatchMapper;
        this.sourceFileMapper = sourceFileMapper;
        this.sourceRecordMapper = sourceRecordMapper;
        this.knowledgeUnitMapper = knowledgeUnitMapper;
        this.knowledgeUnitRelationMapper = knowledgeUnitRelationMapper;
        this.repairCaseImportMapper = repairCaseImportMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.problemCatalog = problemCatalog;
        this.openAiGateway = openAiGateway;
        this.qdrantGateway = qdrantGateway;
        this.self = self;
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
            // V1 在已有案例投影时跳过原始导入，保证重复启动幂等；仍会继续补齐未索引向量。
            int projectionCount = repairCaseImportMapper.countAll();
            if (projectionCount == 0) {
                // Go through the Spring proxy so @Transactional is applied.
                ImportResult result = self.getObject().importExcelKnowledge(sourcePath);
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

    /**
     * 一次事务内完成原始文件登记、原始行落库和维修案例构建。
     * 任何文件结构缺失或业务映射失败都会回滚该批次，避免出现半套知识。
     */
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

        // 先验证三类数据齐全，再写数据库；部件表虽可为空，但其文件与 schema 必须存在。
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

        // 在内存中按业务键建立关联，避免每个维修事件都回查一次数据库。
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
            // 多次到访必须按日期和作业号排序，后续 firstFix/finalResolved 才有业务含义。
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

        sourceFileMapper.markBatchValidated(batchId);
        ingestionBatchMapper.complete(batchId, totalRows);

        return new ImportResult(files.size(), totalRows, caseCount);
    }

    private ParsedFile parse(Path path) throws Exception {
        // DataFormatter 读取 Excel 显示值，可避免日期、数字格式在不同单元格类型下表现不一致。
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
                    // Excel 行号作为证据追溯坐标保留，前端证据可定位回原始资料。
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
        // 业务 Excel 常在表头前包含标题或说明，V1 扫描前 10 行寻找真正列头。
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
        // code 是知识库稳定业务键；ON DUPLICATE KEY 让重复导入保持幂等。
        knowledgeBaseMapper.upsertActive("AI_REPAIR_DEMO", "AI Repair Assistant Demo Knowledge");
        return requireId(knowledgeBaseMapper.findIdByCode("AI_REPAIR_DEMO"), "knowledge base");
    }

    private long createBatch(long knowledgeBaseId, List<ParsedFile> files) {
        // 批次记录支持后续追踪“哪次导入包含哪些文件以及是否完整完成”。
        String batchKey = UUID.randomUUID().toString();
        ingestionBatchMapper.insert(knowledgeBaseId, batchKey, files.size());
        return requireId(ingestionBatchMapper.findIdByBatchKey(batchKey), "ingestion batch");
    }

    private long registerSourceFile(
            long knowledgeBaseId,
            long batchId,
            ParsedFile file) throws Exception {
        // SHA-256 识别文件内容；同内容换文件名不会重复创建事实来源。
        sourceFileMapper.upsert(new SourceFileCommand(knowledgeBaseId, batchId, file.kind().name(),
                file.path().getFileName().toString(), "XLSX", file.kind().name(), "ja-JP",
                file.sha256(), Files.size(file.path()), "POI_V1", false));
        return requireId(sourceFileMapper.findIdByKnowledgeBaseAndSha(knowledgeBaseId, file.sha256()), "source file");
    }

    private void importSourceRows(long sourceFileId, ParsedFile file) {
        for (Map<String, String> row : file.rows()) {
            String businessKey = businessKey(file.kind(), row);
            String rawJson = writeJson(row);
            // source_record 是最原始、不可推断的事实层，保留完整行 JSON 和行级指纹。
            sourceRecordMapper.insertExcelRow(sourceFileId, file.kind().recordType, businessKey,
                    file.sheetName(), integer(row.get("_sourceRow")), rawJson,
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
        // 一个 repair case 的独立事件边界是受付ID，而不是每次上门的作業ID。
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
        // 知识构建阶段也使用同一套 taxonomy，保证离线案例标签与在线问题理解一致。
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

        // 将日文 Excel 列名规范化成稳定的内部 JSON 字段，隔离外部资料格式变化。
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

        // 问题投影用于“当前现象找相似案例”；解决投影用于展示原因、处置和部件。
        // 当前 Qdrant 仅索引 problemProjection，避免答案文本反向污染问题相似度。
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

        // sourceFingerprint 标识原始资料组合，contentFingerprint 标识规范化知识内容。
        // 二者分开后，未来可判断是来源变化还是知识抽取规则变化。
        String sourceFingerprint = sha256(
                writeJson(Map.of(
                        "call", call,
                        "visits", visits,
                        "parts", parts))
                        .getBytes(StandardCharsets.UTF_8));
        String contentJson = writeJson(content);
        String contentFingerprint = sha256(contentJson.getBytes(StandardCharsets.UTF_8));
        // 稳定 UUID 确保重复导入同一受付ID时覆盖 Qdrant 中同一个 point。
        String pointId = UUID.nameUUIDFromBytes(
                ("repair-case:" + receptionId).getBytes(StandardCharsets.UTF_8))
                .toString();
        String unitKey = "REPAIR_CASE:" + receptionId;
        String title = "%s %s 维修案例 %s".formatted(
                model,
                problemTypeLabel,
                receptionId);
        String trustLevel = finalResolved ? "VERIFIED_CASE" : "OBSERVED_CASE";

        // knowledge_unit 表示逻辑知识，knowledge_unit_version 表示可发布的不可变版本。
        knowledgeUnitMapper.upsert(knowledgeBaseId, unitKey, "REPAIR_CASE");
        long unitId = requireId(knowledgeUnitMapper.findId(knowledgeBaseId, unitKey), "knowledge unit");
        repairCaseImportMapper.insertIgnoreVersion(unitId, title, trustLevel, contentJson,
                sourceFingerprint, contentFingerprint, pointId);
        long unitVersionId = requireId(repairCaseImportMapper.findVersionId(unitId), "knowledge unit version");
        repairCaseImportMapper.linkProblemType(unitVersionId, problemMatch.definition().id());
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
        // repair_case_projection_v1 是在线诊断读模型，不取代 knowledge_unit/source_record 真相层。
        repairCaseImportMapper.upsertProjection(new RepairCaseProjectionCommand(unitVersionId, receptionId,
                model, serial, call.getOrDefault("顧客/店舗名", ""), parseDateTime(call.get("受付日時")),
                problemTypeCode, problemTypeLabel, writeJson(errorCodes), complaint, onsiteObservation,
                causeText, actionText, finalResolved, firstFix, visits.size(), totalDuration,
                writeJson(normalizedParts), problemProjection, resolutionProjection, sourceReference,
                pointId, trustLevel));

        // 建立知识到每一条原始记录的反向链路，证据详情未来可回放客服、到访和部件明细。
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
        repairCaseImportMapper.insertSearchProjection(unitVersionId, type, text,
                sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void linkSource(
            long unitVersionId,
            long sourceFileId,
            String recordType,
            String businessKey,
            String relationType) {
        Long sourceId = sourceRecordMapper.findFirstId(sourceFileId, recordType, businessKey);
        if (sourceId != null) {
            knowledgeUnitRelationMapper.insertIgnoreSourceLink(unitVersionId, sourceId, relationType);
        }
    }

    private void indexPendingCases() {
        if (!openAiGateway.enabled()) {
            log.info("OpenAI key is not configured; semantic indexing is deferred");
            return;
        }
        // indexed=false 是 MySQL 与 Qdrant 之间的最终一致性游标。
        List<PendingVector> pending = repairCaseImportMapper.findPendingVectors().stream()
                .map(row -> new PendingVector(row.id(), row.receptionId(), row.model(),
                        row.problemTypeCode(), row.problemProjection(), row.qdrantPointId()))
                .toList();

        // 64 条一批兼顾 API 吞吐和失败重试粒度；失败时保留 indexed=false，下次启动重试。
        for (int offset = 0; offset < pending.size(); offset += 64) {
            List<PendingVector> batch = pending.subList(
                    offset,
                    Math.min(offset + 64, pending.size()));
            List<float[]> embeddings = openAiGateway.embed(
                    batch.stream().map(PendingVector::projection).toList());
            if (embeddings.size() != batch.size()) {
                // 不接受不完整批次，防止向量与 case id 发生位置错配。
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
            // 只有 Qdrant wait=true upsert 成功后才推进 MySQL 索引状态。
            repairCaseImportMapper.markIndexed(batch.stream().map(PendingVector::id).toList());
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

    private long requireId(Long id, String resource) {
        if (id == null) {
            throw new IllegalStateException("Unable to resolve " + resource + " after persistence");
        }
        return id;
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
