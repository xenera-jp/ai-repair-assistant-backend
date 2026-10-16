package com.aifieldservice.repairassistant.service.cost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import tools.jackson.databind.JsonNode;
public final class ModelCostCalculator {
    private ModelCostCalculator(){}
    public record Charge(Long inputTokens,Long cachedTokens,Long audioTokens,Long outputTokens,
        BigDecimal seconds,BigDecimal costUsd,String status,String basis){}
    public static Charge calculate(JsonNode response,ModelPricing.Rate rate,BigDecimal estimatedSeconds){
        JsonNode usage=response.path("usage");
        Long input=count(usage,"input_tokens");
        if(input==null) input=count(usage,"prompt_tokens");
        Long output=count(usage,"output_tokens");
        if(output==null) output=count(usage,"completion_tokens");
        JsonNode details=usage.path("input_tokens_details");
        if(details.isMissingNode()) details=usage.path("input_token_details");
        if(details.isMissingNode()) details=usage.path("prompt_tokens_details");
        Long cached=count(details,"cached_tokens"),audio=count(details,"audio_tokens");
        BigDecimal seconds=usage.path("seconds").isNumber()?new BigDecimal(usage.path("seconds").asText()):null;
        if(seconds==null && response.path("duration").isNumber()) seconds=new BigDecimal(response.path("duration").asText());
        if(rate!=null && java.util.stream.Stream.of(rate.input(),rate.cachedInput(),rate.audioInput(),rate.output(),rate.perMinute(),rate.estimatedPerMinute())
                .anyMatch(value->value!=null && value.signum()<0)) rate=null;
        if(rate==null) return new Charge(input,cached,audio,output,seconds,null,"PRICE_MISSING","UNKNOWN");
        BigDecimal cost=null; boolean estimated=false; String basis="TOKENS";
        if(rate.perMinute()!=null){
            basis="DURATION";
            if(seconds==null && estimatedSeconds!=null){seconds=estimatedSeconds;estimated=true;}
            if(seconds!=null && seconds.signum()>=0) cost=seconds.multiply(rate.perMinute()).divide(BigDecimal.valueOf(60),12,RoundingMode.HALF_UP);
        }else if(input!=null && (output!=null || rate.output()==null)){
            long cache=cached==null?0:cached,sound=audio==null?0:audio;
            if((rate.audioInput()==null || audio!=null) && cache+sound<=input && rate.input()!=null && (cache==0||rate.cachedInput()!=null) && (sound==0||rate.audioInput()!=null)){
                cost=BigDecimal.valueOf(input-cache-sound).multiply(rate.input());
                if(cache>0) cost=cost.add(BigDecimal.valueOf(cache).multiply(rate.cachedInput()));
                if(sound>0) cost=cost.add(BigDecimal.valueOf(sound).multiply(rate.audioInput()));
                // output_tokens already includes reasoning tokens; do not add them twice.
                if(output!=null && rate.output()!=null) cost=cost.add(BigDecimal.valueOf(output).multiply(rate.output()));
                cost=cost.divide(BigDecimal.valueOf(1000000),12,RoundingMode.HALF_UP);
            }
        }
        if(cost==null && rate.estimatedPerMinute()!=null){
            if(seconds==null)seconds=estimatedSeconds;
            if(seconds!=null && seconds.signum()>=0){
                cost=seconds.multiply(rate.estimatedPerMinute()).divide(BigDecimal.valueOf(60),12,RoundingMode.HALF_UP);
                estimated=true;basis="DURATION";
            }
        }
        return new Charge(input,cached,audio,output,seconds,cost,cost==null?"USAGE_MISSING":estimated?"ESTIMATED":"CALCULATED",basis);
    }
    private static Long count(JsonNode n,String f){return n.path(f).isIntegralNumber()&&n.path(f).asLong()>=0?n.path(f).asLong():null;}
}
