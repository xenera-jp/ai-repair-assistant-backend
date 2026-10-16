package com.aifieldservice.repairassistant.service.cost;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/** USD per million tokens (or per minute). Exact keys avoid pricing an unrelated model. */
@Component
@ConfigurationProperties(prefix="repair-assistant.model-pricing")
public class ModelPricing {
    public record Rate(BigDecimal input, BigDecimal cachedInput, BigDecimal audioInput, BigDecimal output, BigDecimal perMinute, BigDecimal estimatedPerMinute) {}
    private Map<String,Rate> models=new LinkedHashMap<>();
    public Map<String,Rate> getModels(){return models;}
    public void setModels(Map<String,Rate> models){this.models=models;}
}
