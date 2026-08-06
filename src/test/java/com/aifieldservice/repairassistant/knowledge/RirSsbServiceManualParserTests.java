package com.aifieldservice.repairassistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualDocument;
import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualUnit;

class RirSsbServiceManualParserTests {

    private static final Path MANUAL = Path.of(
            "data",
            "knowledge",
            "RIR1-SSB service manual.pdf");

    private final RirSsbServiceManualParser parser = new RirSsbServiceManualParser();

    @Test
    void extractsReviewedE4KnowledgeUnitsWithSourceLocations() throws Exception {
        ManualDocument manual = parser.parse(MANUAL);

        assertThat(manual.model()).isEqualTo("RIR1-SSB");
        assertThat(manual.pageCount()).isEqualTo(106);
        assertThat(manual.units()).hasSize(3);

        ManualUnit definition = unit(manual, "FAULT_DEFINITION:RIR1-SSB:E4");
        assertThat(definition.problemTypeCode()).isEqualTo("HIGH_PRESSURE_CONDENSATION");
        assertThat(definition.errorCode()).isEqualTo("E4");
        assertThat(definition.sourcePage().pdfPageIndex()).isEqualTo(36);
        assertThat(definition.sourcePage().printedPageLabel()).isEqualTo("31");
        assertThat(definition.summary()).contains("1 小时内触发 3 次", "5 次后压缩机停止");
        assertThat(definition.sourceAnchor()).isEqualTo("High-Pressure Alarm");
        assertThat(definition.sourceRegion().pageWidth()).isEqualTo(612.0);
        assertThat(definition.sourceRegion().pageHeight()).isEqualTo(792.0);
        assertThat(definition.sourceRegion().x()).isBetween(187.0, 190.0);
        assertThat(definition.sourceRegion().y()).isBetween(496.0, 498.0);

        ManualUnit procedure = unit(manual, "REPAIR_PROCEDURE:RIR1-SSB:E4:CHECK");
        assertThat(procedure.sourcePage().pdfPageIndex()).isEqualTo(59);
        assertThat(procedure.sourcePage().printedPageLabel()).isEqualTo("54");
        assertThat(procedure.sourceRegion()).isNotNull();
        assertThat(procedure.actionSteps()).contains(
                "检查空气过滤网和冷凝器是否清洁、是否存在堵塞。",
                "检查控制板 K6-6 与 K5-2 之间是否有 5VDC；缺失时检查控制板。");

        ManualUnit causes = unit(manual, "REPAIR_PROCEDURE:RIR1-SSB:E4:CAUSES");
        assertThat(causes.sourcePage().pdfPageIndex()).isEqualTo(60);
        assertThat(causes.sourcePage().printedPageLabel()).isEqualTo("55");
        assertThat(causes.sourceRegion()).isNotNull();
        assertThat(causes.candidateCodes()).containsExactly(
                "CONDENSER_FILTER_CLOGGING",
                "REFRIGERANT_CHARGE_OR_CIRCUIT_ABNORMALITY",
                "HIGH_PRESSURE_SWITCH_ABNORMALITY");
    }

    private ManualUnit unit(ManualDocument manual, String key) {
        return manual.units().stream()
                .filter(item -> item.unitKey().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
