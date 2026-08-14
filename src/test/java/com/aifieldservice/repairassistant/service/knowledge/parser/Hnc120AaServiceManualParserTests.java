package com.aifieldservice.repairassistant.service.knowledge.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.ManualDocument;

class Hnc120AaServiceManualParserTests {

    private static final Path MANUAL = Path.of(
            "data", "knowledge", "HNC-120AA service manual.pdf");

    private final Hnc120AaServiceManualParser parser = new Hnc120AaServiceManualParser();

    @Test
    void extractsFourServiceDiagnosisPathsFromTheReviewedTable() throws Exception {
        ManualDocument manual = parser.parse(MANUAL);

        assertThat(manual.model()).isEqualTo("HNC-120AA");
        assertThat(manual.pageCount()).isEqualTo(28);
        assertThat(manual.units()).hasSize(4);
        assertThat(manual.units())
                .extracting(ServiceManualKnowledge.ManualUnit::problemTypeCode)
                .containsExactly(
                        "EQUIPMENT_NO_START_HNC",
                        "COOLING_INSUFFICIENT_HNC",
                        "CABINET_DRYNESS_HNC",
                        "ENVIRONMENTAL_FROST_HNC");
        assertThat(manual.units())
                .allSatisfy(unit -> {
                    assertThat(unit.sourcePage().pdfPageIndex()).isPositive();
                    assertThat(unit.sourceRegion().pageWidth()).isPositive();
                    assertThat(unit.summaryJa()).isNotBlank();
                    assertThat(unit.candidateCodes()).isNotEmpty();
                });
    }
}
