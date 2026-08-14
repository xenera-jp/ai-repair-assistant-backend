package com.aifieldservice.repairassistant.service.diagnosis.semantic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aifieldservice.repairassistant.integration.openai.OpenAiGateway;
import com.aifieldservice.repairassistant.integration.openai.SemanticProblemUnderstandingResponse;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemTypeDefinition;

/** 语义理解用例：准备最小上下文、调用网关、再验证输出。 */
@Service
public class SemanticProblemUnderstandingService {

    private static final List<String> ALLOWED_FIELDS = List.of(
            "errorCode", "operatingState", "occurrence", "measurement", "environment", "recentChanges");

    private final OpenAiGateway openAiGateway;
    private final SemanticUnderstandingValidator validator;

    public SemanticProblemUnderstandingService(
            OpenAiGateway openAiGateway,
            SemanticUnderstandingValidator validator) {
        this.openAiGateway = openAiGateway;
        this.validator = validator;
    }

    public boolean enabled() {
        return openAiGateway.enabled();
    }

    public Optional<SemanticProblemUnderstandingResponse> understand(
            String originalText, String language, List<ProblemTypeDefinition> taxonomy) {
        List<Map<String, Object>> restrictedTaxonomy = taxonomy.stream()
                .map(item -> Map.<String, Object>of(
                        "code", item.code(),
                        "nameZh", item.nameZh(),
                        "nameJa", item.nameJa(),
                        "aliases", item.aliases(),
                        "modelScopes", item.modelScopes(),
                        "errorCodes", item.errorCodes().stream().map(error -> error.code()).toList()))
                .toList();
        return openAiGateway.understandProblem(originalText, language, restrictedTaxonomy, ALLOWED_FIELDS)
                .flatMap(result -> validator.validate(result, originalText, taxonomy));
    }
}
