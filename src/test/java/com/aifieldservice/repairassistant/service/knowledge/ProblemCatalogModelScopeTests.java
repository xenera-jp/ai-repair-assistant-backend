package com.aifieldservice.repairassistant.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.aifieldservice.repairassistant.dao.knowledge.ProblemCatalogMapper;
import com.aifieldservice.repairassistant.domain.knowledge.model.ProblemTypeRecord;
import com.aifieldservice.repairassistant.service.knowledge.impl.ProblemCatalogServiceImpl;
import tools.jackson.databind.ObjectMapper;

class ProblemCatalogModelScopeTests {
    private final ProblemCatalogMapper mapper = mock(ProblemCatalogMapper.class);
    private final ProblemCatalogServiceImpl catalog = new ProblemCatalogServiceImpl(mapper, new ObjectMapper());

    @Test
    void routesChineseAndJapaneseComplaintToRirInsteadOfHnc() {
        when(mapper.findAllActive()).thenReturn(List.of(
                row("CABINET_HIGH_TEMP", "[\"冷却不良(庫内高温)\"]", "[\"不冷\",\"庫内が冷えない\"]",
                        "[\"RIR1-SSB\",\"FH1-SSB\"]", "[{\"code\":\"E1\",\"models\":[\"RIR1-SSB\",\"FH1-SSB\"]}]"),
                row("COOLING_INSUFFICIENT_HNC", "[\"冷却不良\"]", "[\"冷えない\"]", "[\"HNC-120AA\"]", "[]")));
        List<String> complaints = List.of(
                "设备型号：RIR1-SSB。主要症状：冰箱内部完全不制冷（制冷故障）。客户反映，冰箱在一天中的某些时段制冷效果不佳，今天彻底失效。故障代码：E1。当前运行状态：冰箱门已正确关闭（客户确认）；客户反映未看到滤网；维修人员确认滤网和冰箱门均无问题。发生时间和频率：症状始于营业结束前后，今天进一步恶化。现场测量：冰箱内部温度约为9摄氏度。",
                "機器型式：RIR1-SSB。主な症状：庫内が全然冷えていない（冷却不良）。時間帯による冷えの追いつかなさを感じており、今日は完全に駄目になっていると報告。エラーコード：E1。現在の運転状態：扉はちゃんと閉まっている（顧客確認）；フィルターは見当たらないと顧客報告；サービス側はフィルターと扉に問題がないと述べている。発生時期・頻度：症状は営業終了あたりから発生し、本日さらに悪化している。現場測定値：庫内の温度計は約9度前後。");
        for (String complaint : complaints) {
            var candidates = catalog.matchCandidates("RIR1-SSB", "E1", complaint);
            assertThat(candidates).hasSize(1);
            assertThat(candidates.getFirst().definition().code()).isEqualTo("CABINET_HIGH_TEMP");
            assertThat(candidates.getFirst().score()).isEqualTo(67);
        }
        assertThat(catalog.match("HNC-120AA", "", "冷却不良").orElseThrow().definition().code())
                .isEqualTo("COOLING_INSUFFICIENT_HNC");
    }

    @Test
    void doesNotAwardErrorCodePointsForAnotherModel() {
        when(mapper.findAllActive()).thenReturn(List.of(row("CABINET_HIGH_TEMP", "[]", "[]",
                "[\"RIR1-SSB\",\"FH1-SSB\"]", "[{\"code\":\"E1\",\"models\":[\"FH1-SSB\"]}]")));
        assertThat(catalog.matchCandidates("RIR1-SSB", "E1", "E1")).isEmpty();
        assertThat(catalog.match("fh1-ssb", "e1", "E1").orElseThrow().score()).isEqualTo(67);
    }

    private ProblemTypeRecord row(String code, String labels, String aliases, String models, String errors) {
        return new ProblemTypeRecord(1, code, code, code, labels, aliases, models, errors, "[]");
    }
}
