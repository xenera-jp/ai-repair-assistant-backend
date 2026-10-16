package com.aifieldservice.repairassistant.service.cost;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class ModelCostCalculatorTests {
    private final ObjectMapper json=new ObjectMapper();
    private static BigDecimal d(String v){return new BigDecimal(v);}
    private final ModelPricing.Rate text=new ModelPricing.Rate(d("0.25"),d("0.025"),null,d("2"),null,null);
    private ModelCostCalculator.Charge charge(String body,ModelPricing.Rate rate,BigDecimal seconds){return ModelCostCalculator.calculate(json.readTree(body),rate,seconds);}
    private void cost(String expected,ModelCostCalculator.Charge c){assertNotNull(c.costUsd());assertEquals(0,d(expected).compareTo(c.costUsd()));}
    @Test void subtractsCachedInputAndDoesNotDoubleBillReasoning(){
        var c=charge("""
            {"usage":{"input_tokens":10000,"input_tokens_details":{"cached_tokens":4000},"output_tokens":2000,"output_tokens_details":{"reasoning_tokens":1500}}}
            """,text,null);
        cost("0.0056",c);assertEquals("CALCULATED",c.status());assertEquals(2000L,c.outputTokens());
    }
    @Test void distinguishesMissingUsageFromFreeCall(){
        var c=charge("{}",text,null);assertNull(c.costUsd());assertEquals("USAGE_MISSING",c.status());
        cost("0",charge("{\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}",text,null));
    }
    @Test void unknownModelIsNotPricedUsingDefaultModel(){
        assertEquals("PRICE_MISSING",charge("{\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}",null,null).status());
    }
    @Test void usesDurationForRealtimeEvenWhenTokensArePresent(){
        var rate=new ModelPricing.Rate(null,null,null,null,d("0.017"),null);
        cost("0.017",charge("{\"usage\":{\"seconds\":60,\"input_tokens\":100,\"output_tokens\":100}}",rate,null));
        var estimated=charge("{}",rate,d("30"));cost("0.0085",estimated);assertEquals("ESTIMATED",estimated.status());
    }
    @Test void diarizationUsesAudioAndTextTokenRates(){
        var rate=new ModelPricing.Rate(d("2.5"),null,d("6"),d("10"),null,d("0.006"));
        cost("0.0165",charge("{\"usage\":{\"input_tokens\":3000,\"input_token_details\":{\"audio_tokens\":2000,\"text_tokens\":1000},\"output_tokens\":200}}",rate,d("60")));
        var fallback=charge("{\"duration\":15}",rate,null);cost("0.0015",fallback);assertEquals("ESTIMATED",fallback.status());
    }
    @Test void embeddingSupportsPromptTokensAndNoOutput(){
        cost("0.00002",charge("{\"usage\":{\"prompt_tokens\":1000,\"total_tokens\":1000}}",new ModelPricing.Rate(d("0.02"),null,null,null,null,null),null));
    }
    @Test void invalidUsageDoesNotProduceNegativeCost(){
        assertNull(charge("{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":101},\"output_tokens\":10}}",text,null).costUsd());
    }
}
