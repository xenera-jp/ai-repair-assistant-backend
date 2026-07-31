package com.aifieldservice.repairassistant.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProblemCatalogService {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProblemCatalogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ProblemTypeDefinition> all() {
        return jdbcTemplate.query("""
                SELECT id, code, name_zh, name_ja, source_labels_json,
                       aliases_json, model_scopes_json, error_codes_json,
                       clarification_schema_json
                FROM problem_type
                ORDER BY implementation_priority, id
                """, (resultSet, rowNum) -> new ProblemTypeDefinition(
                resultSet.getLong("id"),
                resultSet.getString("code"),
                resultSet.getString("name_zh"),
                resultSet.getString("name_ja"),
                readStringList(resultSet.getString("source_labels_json")),
                readStringList(resultSet.getString("aliases_json")),
                readStringList(resultSet.getString("model_scopes_json")),
                readErrorCodes(resultSet.getString("error_codes_json")),
                readClarifications(resultSet.getString("clarification_schema_json"))));
    }

    public Optional<ProblemMatch> match(
            String model,
            String errorCode,
            String text) {
        String normalizedText = normalize(text);
        String normalizedModel = normalize(model);
        String normalizedError = normalize(errorCode);

        return all().stream()
                .map(definition -> score(
                        definition,
                        normalizedModel,
                        normalizedError,
                        normalizedText))
                .filter(match -> match.score() >= 35)
                .max(Comparator
                        .comparingInt(ProblemMatch::score)
                        .thenComparing(match -> match.definition().code()));
    }

    public Optional<ProblemTypeDefinition> findByCode(String code) {
        return all().stream().filter(item -> item.code().equals(code)).findFirst();
    }

    private ProblemMatch score(
            ProblemTypeDefinition definition,
            String model,
            String errorCode,
            String text) {
        int score = 0;
        List<String> signals = new ArrayList<>();

        if (!model.isBlank()
                && definition.modelScopes().stream()
                        .map(this::normalize)
                        .anyMatch(model::equals)) {
            score += 12;
            signals.add("型号适配");
        }
        if (!errorCode.isBlank()
                && definition.errorCodes().stream()
                        .map(ErrorCodeDefinition::code)
                        .map(this::normalize)
                        .anyMatch(errorCode::equals)) {
            score += 55;
            signals.add("错误码匹配");
        }
        for (String label : definition.sourceLabels()) {
            if (contains(text, label)) {
                score += 70;
                signals.add("故障模式精确匹配");
                break;
            }
        }
        for (String alias : definition.aliases()) {
            if (contains(text, alias)) {
                score += 32;
                signals.add("症状语义匹配");
                break;
            }
        }

        return new ProblemMatch(definition, Math.min(score, 100), signals);
    }

    private boolean contains(String normalizedText, String candidate) {
        String normalizedCandidate = normalize(candidate);
        return !normalizedCandidate.isBlank()
                && normalizedText.contains(normalizedCandidate);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.strip()
                        .replace('／', '/')
                        .replace(" ", "")
                        .toUpperCase(Locale.ROOT);
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<ErrorCodeDefinition> readErrorCodes(String json) {
        try {
            List<Map<String, Object>> values = objectMapper.readValue(
                    json,
                    new TypeReference<>() {
                    });
            return values.stream()
                    .map(value -> new ErrorCodeDefinition(
                            String.valueOf(value.getOrDefault("code", "")),
                            value.get("models") instanceof List<?> models
                                    ? models.stream().map(String::valueOf).toList()
                                    : List.of()))
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<ClarificationDefinition> readClarifications(String json) {
        try {
            List<Map<String, Object>> values = objectMapper.readValue(
                    json,
                    new TypeReference<>() {
                    });
            return values.stream()
                    .map(value -> new ClarificationDefinition(
                            String.valueOf(value.getOrDefault("field", "")),
                            String.valueOf(value.getOrDefault("level", "C")),
                            String.valueOf(value.getOrDefault(
                                    "questionZh",
                                    "请补充相关现场信息。"))))
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    public record ProblemTypeDefinition(
            long id,
            String code,
            String nameZh,
            String nameJa,
            List<String> sourceLabels,
            List<String> aliases,
            List<String> modelScopes,
            List<ErrorCodeDefinition> errorCodes,
            List<ClarificationDefinition> clarifications) {
    }

    public record ErrorCodeDefinition(String code, List<String> models) {
    }

    public record ClarificationDefinition(String field, String level, String questionZh) {
    }

    public record ProblemMatch(
            ProblemTypeDefinition definition,
            int score,
            List<String> matchedSignals) {
    }
}
