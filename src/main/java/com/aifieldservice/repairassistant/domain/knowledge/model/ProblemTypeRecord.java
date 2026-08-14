package com.aifieldservice.repairassistant.domain.knowledge.model;

/** 故障类别目录的持久化记录；JSON 字段保存别名、适用机型和澄清规则。 */
public record ProblemTypeRecord(long id, String code, String nameZh, String nameJa,
        String sourceLabelsJson, String aliasesJson, String modelScopesJson,
        String errorCodesJson, String clarificationSchemaJson) {
}
