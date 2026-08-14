package com.aifieldservice.repairassistant.service.knowledge.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.ManualDocument;

class Fh1AacServiceManualParserTests {

    private static final Path MANUAL = Path.of(
            "data", "knowledge", "FH1-AAC service manual.pdf");

    private final Fh1AacServiceManualParser parser = new Fh1AacServiceManualParser();

    @Test
    void extractsReviewedDiagnosisUnitsWithTraceableSourceRegions() throws Exception {
        ManualDocument manual = parser.parse(MANUAL);

        assertThat(manual.model()).isEqualTo("FH1-AAC");
        assertThat(manual.pageCount()).isEqualTo(44);
        assertThat(manual.units()).hasSize(9);
        assertThat(manual.units())
                .allSatisfy(unit -> {
                    assertThat(unit.sourcePage().pdfPageIndex()).isPositive();
                    assertThat(unit.sourceAnchor()).isNotBlank();
                    assertThat(unit.sourceRegion().pageWidth()).isPositive();
                    assertThat(unit.sourceRegion().pageHeight()).isPositive();
                    assertThat(unit.summaryJa()).isNotBlank();
                    assertThat(unit.actionStepsJa()).isNotEmpty();
                    assertThat(unit.candidateCodes()).isNotEmpty();
                });
        assertThat(manual.units())
                .extracting(ServiceManualKnowledge.ManualUnit::problemTypeCode)
                .contains(
                        "COMPRESSOR_START_FAILURE",
                        "CABINET_HIGH_TEMP",
                        "DEFROST_FAILURE_FROST",
                        "HIGH_PRESSURE_CONDENSATION");
    }
}
