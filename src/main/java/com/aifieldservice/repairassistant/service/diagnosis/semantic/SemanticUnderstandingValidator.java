package com.aifieldservice.repairassistant.service.diagnosis.semantic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.integration.openai.SemanticProblemUnderstandingResponse;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemTypeDefinition;

/** 对模型输出施加 taxonomy、字段白名单和原文证据三重约束。 */
@Component
public class SemanticUnderstandingValidator {

    private static final Logger log = LoggerFactory.getLogger(SemanticUnderstandingValidator.class);
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "errorCode", "operatingState", "occurrence", "measurement", "environment", "recentChanges");
    private static final double DEFAULT_CLASSIFICATION_CONFIDENCE = 0.70;

    private final RepairAssistantProperties properties;

    public SemanticUnderstandingValidator(RepairAssistantProperties properties) {
        this.properties = properties;
    }

    public Optional<SemanticProblemUnderstandingResponse> validate(
            SemanticProblemUnderstandingResponse response,
            String originalText,
            List<ProblemTypeDefinition> taxonomy) {
        if (response == null || response.problemTypeCode() == null) {
            return Optional.empty();
        }
        double classificationConfidence = normalizeClassificationConfidence(response.classificationConfidence());
        if (classificationConfidence < properties.problemUnderstanding().llmClassificationAcceptScore()) {
            return Optional.empty();
        }
        String problemTypeCode = canonicalProblemTypeCode(response.problemTypeCode(), taxonomy);
        if (problemTypeCode == null) {
            return Optional.empty();
        }

        Map<String, SemanticProblemUnderstandingResponse.SemanticField> fields = response.fields() == null
                ? Map.of() : response.fields();
        Map<String, SemanticProblemUnderstandingResponse.SemanticField> validFields = fields.entrySet().stream()
                .filter(entry -> isValidField(entry.getKey(), entry.getValue(), originalText))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return Optional.of(new SemanticProblemUnderstandingResponse(
                problemTypeCode, classificationConfidence,
                response.classificationReason(), validFields));
    }

    private double normalizeClassificationConfidence(Double confidence) {
        if (confidence == null || confidence < 0 || confidence > 1) {
            log.warn("LLM classification confidence is missing or invalid; using conservative default {}",
                    DEFAULT_CLASSIFICATION_CONFIDENCE);
            return DEFAULT_CLASSIFICATION_CONFIDENCE;
        }
        return confidence;
    }

    private String canonicalProblemTypeCode(String value, List<ProblemTypeDefinition> taxonomy) {
        String normalized = value.strip();
        if ("UNCLASSIFIED".equalsIgnoreCase(normalized)) {
            return "UNCLASSIFIED";
        }
        return taxonomy.stream()
                // Models are instructed to return code, but accepting reviewed labels makes
                // the boundary resilient when it echoes an alias or source-system label.
                .filter(item -> matchesTaxonomyValue(item, normalized))
                .map(ProblemTypeDefinition::code)
                .findFirst()
                .orElse(null);
    }

    private boolean matchesTaxonomyValue(ProblemTypeDefinition item, String value) {
        return sameTaxonomyValue(item.code(), value)
                || sameTaxonomyValue(item.nameZh(), value)
                || sameTaxonomyValue(item.nameJa(), value)
                || item.aliases().stream().anyMatch(candidate -> sameTaxonomyValue(candidate, value))
                || item.sourceLabels().stream().anyMatch(candidate -> sameTaxonomyValue(candidate, value));
    }

    private boolean sameTaxonomyValue(String left, String right) {
        return normalizeTaxonomyValue(left).equals(normalizeTaxonomyValue(right));
    }

    private String normalizeTaxonomyValue(String value) {
        return value == null ? "" : value.strip()
                .replace('／', '/')
                .replace('・', '/')
                .replace(" ", "")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private boolean isValidField(
            String code,
            SemanticProblemUnderstandingResponse.SemanticField field,
            String originalText) {
        if (!ALLOWED_FIELDS.contains(code) || field == null
                || !Set.of("PRESENT", "ABSENT").contains(field.status())
                || field.confidence() == null
                || field.confidence() < 0 || field.confidence() > 1
                || field.evidence() == null || field.evidence().isBlank()
                || field.evidence().length() > 300) {
            return false;
        }
        return field.value() != null && !field.value().isBlank() && field.value().length() <= 200;
    }
}
