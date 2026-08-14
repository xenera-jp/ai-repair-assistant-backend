package com.aifieldservice.repairassistant.service.knowledge.impl;

import com.aifieldservice.repairassistant.service.knowledge.*;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aifieldservice.repairassistant.dao.knowledge.ProblemCatalogMapper;
import com.aifieldservice.repairassistant.domain.knowledge.model.ProblemTypeRecord;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 从 MySQL 中读取领域问题分类，并执行可解释的规则匹配。
 *
 * <p>问题类型不是由 LLM 临时创造，而是由 Flyway 中预先审查的 taxonomy 定义。
 * 这样每个分类都能绑定固定的检索策略、候选原因和现场追问模板。
 */
@Service
public class ProblemCatalogServiceImpl implements ProblemCatalogService {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final ProblemCatalogMapper problemCatalogMapper;
    private final ObjectMapper objectMapper;

    public ProblemCatalogServiceImpl(ProblemCatalogMapper problemCatalogMapper, ObjectMapper objectMapper) {
        this.problemCatalogMapper = problemCatalogMapper;
        this.objectMapper = objectMapper;
    }

    public List<ProblemTypeDefinition> all() {
        // 当前数据量很小，V1 直接读取活动 taxonomy；正式版可按 taxonomy version 做缓存。
        return problemCatalogMapper.findAllActive().stream()
                .map(this::toDefinition)
                .toList();
    }

    public Optional<ProblemMatch> match(
            String model,
            String errorCode,
            String text) {
        return matchCandidates(model, errorCode, text).stream().findFirst();
    }

    @Override
    public List<ProblemMatch> matchCandidates(String model, String errorCode, String text) {
        String normalizedText = normalize(text);
        String normalizedModel = normalize(model);
        String normalizedError = normalize(errorCode);

        // 低于 35 分视为“没有足够信号”，宁可返回未分类，也不强行匹配一个类别。
        return all().stream()
                .map(definition -> score(definition, normalizedModel, normalizedError, normalizedText))
                .filter(match -> match.score() >= 35)
                .sorted(Comparator.comparingInt(ProblemMatch::score).reversed()
                        .thenComparing(match -> match.definition().code()))
                .toList();
    }

    public Optional<ProblemTypeDefinition> findByCode(String code) {
        return all().stream().filter(item -> item.code().equals(code)).findFirst();
    }

    private ProblemTypeDefinition toDefinition(ProblemTypeRecord row) {
        return new ProblemTypeDefinition(
                row.id(), row.code(), row.nameZh(), row.nameJa(),
                readStringList(row.sourceLabelsJson()), readStringList(row.aliasesJson()),
                readStringList(row.modelScopesJson()), readErrorCodes(row.errorCodesJson()),
                readClarifications(row.clarificationSchemaJson()));
    }

    private ProblemMatch score(
            ProblemTypeDefinition definition,
            String model,
            String errorCode,
            String text) {
        int score = 0;
        List<String> signals = new ArrayList<>();

        // 型号是适用范围门槛，但仅凭型号不能确定故障类别，所以只给少量支持分。
        if (!model.isBlank()
                && definition.modelScopes().stream()
                        .map(this::normalize)
                        .anyMatch(model::equals)) {
            score += 12;
            signals.add("型号适配");
        }
        // 错误码是高辨识度的结构化信号，权重显著高于普通别名。
        if (!errorCode.isBlank()
                && definition.errorCodes().stream()
                        .map(ErrorCodeDefinition::code)
                        .map(this::normalize)
                        .anyMatch(errorCode::equals)) {
            score += 55;
            signals.add("错误码匹配");
        }
        // sourceLabels 来自原维修数据中的标准故障模式，命中时最可信。
        for (String label : definition.sourceLabels()) {
            if (contains(text, label)) {
                score += 70;
                signals.add("故障模式精确匹配");
                break;
            }
        }
        // aliases 覆盖客户或工程师的口语描述，只作为语义提示，不替代标准故障模式。
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
            // 种子配置异常时单项降级为空列表，避免整个服务因一个可选字段无法启动。
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




}
