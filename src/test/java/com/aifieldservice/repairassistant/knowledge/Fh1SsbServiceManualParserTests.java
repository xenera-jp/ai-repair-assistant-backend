package com.aifieldservice.repairassistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualDocument;
import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualUnit;

class Fh1SsbServiceManualParserTests {

    private static final Path MANUAL = Path.of(
            "data", "knowledge", "FH1-SSB service manual.pdf");

    private final Fh1SsbServiceManualParser parser = new Fh1SsbServiceManualParser();

    @Test
    void extractsReviewedAlarmDefinitionsAndJapaneseInterpretations() throws Exception {
        ManualDocument manual = parser.parse(MANUAL);

        assertThat(manual.model()).isEqualTo("FH1-SSB");
        assertThat(manual.pageCount()).isEqualTo(68);
        assertThat(manual.units()).hasSize(10);
        assertThat(manual.units()).extracting(ManualUnit::errorCode)
                .containsExactly("E1", "E2", "E3", "E4", "E6", "E7", "E8", "E9", "E10", "CF");

        ManualUnit e6 = unit(manual, "E6");
        assertThat(e6.problemTypeCode()).isEqualTo("COMPRESSOR_START_FAILURE");
        assertThat(e6.sourceAnchor()).isEqualTo("High Voltage Alarm");
        assertThat(e6.sourceRegion().pageWidth()).isPositive();
        assertThat(e6.summaryJa()).contains("E6");

        ManualUnit cf = unit(manual, "CF");
        assertThat(cf.problemTypeCode()).isEqualTo("HIGH_PRESSURE_CONDENSATION");
        assertThat(cf.actionStepsJa()).isNotEmpty();

        assertThat(manual.units())
                .allSatisfy(unit -> assertThat(unit.candidateCodes()).isNotEmpty());
    }

    private ManualUnit unit(ManualDocument manual, String errorCode) {
        return manual.units().stream()
                .filter(item -> item.errorCode().equals(errorCode))
                .findFirst()
                .orElseThrow();
    }
}
