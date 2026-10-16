package com.aifieldservice.repairassistant.service.cost;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
@Service
public class ModelCostService {
    public static final String HEADER="X-Model-Cost-Scope";
    private final JdbcTemplate jdbc; private final ModelPricing pricing; private final ObjectMapper json;
    public ModelCostService(JdbcTemplate jdbc,ModelPricing pricing,ObjectMapper json){this.jdbc=jdbc;this.pricing=pricing;this.json=json;}
    public record Entry(long id,String category,String model,Long inputTokens,Long cachedTokens,Long audioTokens,
        Long outputTokens,BigDecimal audioSeconds,BigDecimal costUsd,String status,String basis,String pricingJson,String createdAt){}
    public record Summary(BigDecimal totalUsd,BigDecimal withoutSpeakerProcessingUsd,BigDecimal diarizationUsd,
        BigDecimal roleRecognitionUsd,long unpricedCalls,long estimatedCalls,List<Entry> calls){}
    public String requestScope(){
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a){
            String scope=a.getRequest().getHeader(HEADER);
            if(scope!=null && scope.matches("[A-Za-z0-9_-]{1,64}")) return scope;
        }
        return null;
    }
    public String fileScope(long id){return jdbc.queryForObject("SELECT b.batch_key FROM recording_file f JOIN recording_batch b ON b.id=f.batch_id WHERE f.id=?",String.class,id);}
    public String fileScope(String key){return jdbc.queryForObject("SELECT b.batch_key FROM recording_file f JOIN recording_batch b ON b.id=f.batch_id WHERE f.file_key=?",String.class,key);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String scope,String category,String model,JsonNode response,BigDecimal seconds){
        record(scope,category,model,response,seconds,"call_"+UUID.randomUUID());
    }
    /** Separate transaction retains usage when business parsing or persistence subsequently fails. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String scope,String category,String model,JsonNode response,BigDecimal seconds,String callKey){
        if(scope==null)return;
        var rate=pricing.getModels().get(model);
        var c=ModelCostCalculator.calculate(response==null?json.createObjectNode():response,rate,seconds);
        try{
            jdbc.update("INSERT INTO model_call_cost (call_key,scope_key,category,model,input_tokens,cached_tokens,audio_tokens,output_tokens,audio_seconds,cost_usd,status,basis,pricing_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                callKey,scope,category,model,c.inputTokens(),c.cachedTokens(),c.audioTokens(),c.outputTokens(),c.seconds(),c.costUsd(),c.status(),c.basis(),json.writeValueAsString(rate));
        }catch(org.springframework.dao.DuplicateKeyException ignored){/* Redelivered realtime event, not another charge. */}
    }
    public record HistoryEntry(String id,String scopeKey,String functionCode,String createdAt,BigDecimal costUsd,String status){}
    /** Publish cumulative function costs only after a successful business step. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(String scope,String function){
        if(scope==null)return;
        String categories=switch(function){
            case "REALTIME_TRANSCRIPTION" -> "'REALTIME_TRANSCRIPTION'";
            case "DEVICE_EXTRACTION" -> "'INFORMATION_EXTRACTION','IDENTIFIER_MATCHING'";
            case "PROBLEM_UNDERSTANDING" -> "'PROBLEM_UNDERSTANDING'";
            case "DIAGNOSIS" -> "'DIAGNOSIS','EMBEDDING'";
            case "TOTAL" -> "'REALTIME_TRANSCRIPTION','INFORMATION_EXTRACTION','IDENTIFIER_MATCHING','PROBLEM_UNDERSTANDING','DIAGNOSIS','EMBEDDING'";
            default -> throw new IllegalArgumentException("Unknown cost function");
        };
        var values=jdbc.queryForMap("SELECT COALESCE(SUM(cost_usd),0) amount,COUNT(*)-COUNT(cost_usd) missing,COALESCE(SUM(CASE WHEN status='ESTIMATED' THEN 1 ELSE 0 END),0) estimated FROM model_call_cost WHERE scope_key=? AND category IN ("+categories+")",scope);
        jdbc.update("""
            INSERT INTO model_cost_stage(scope_key,function_code,cost_usd,unpriced_calls,estimated_calls)
            VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE cost_usd=VALUES(cost_usd),unpriced_calls=VALUES(unpriced_calls),estimated_calls=VALUES(estimated_calls),updated_at=CURRENT_TIMESTAMP(6)
            """,scope,function,values.get("amount"),values.get("missing"),values.get("estimated"));
        if("DIAGNOSIS".equals(function))complete(scope,"TOTAL");
    }
    public List<HistoryEntry> history(){
        record Stage(String scope,String function,String time,BigDecimal amount,long missing,long estimated){}
        var stages=jdbc.query("""
            SELECT s.* FROM model_cost_stage s JOIN
            (SELECT scope_key,MAX(updated_at) activity FROM model_cost_stage GROUP BY scope_key ORDER BY activity DESC,scope_key LIMIT 20) recent
            ON recent.scope_key=s.scope_key ORDER BY recent.activity DESC,s.scope_key,s.updated_at,s.function_code
            """,(r,n)->new Stage(r.getString("scope_key"),r.getString("function_code"),r.getString("updated_at"),r.getBigDecimal("cost_usd"),r.getLong("unpriced_calls"),r.getLong("estimated_calls")));
        var groups=new java.util.LinkedHashMap<String,java.util.List<Stage>>();
        for(var stage:stages)groups.computeIfAbsent(stage.scope(),key->new java.util.ArrayList<>()).add(stage);
        var result=new java.util.ArrayList<HistoryEntry>();
        for(var group:groups.entrySet()){
            Stage total=null;
            for(var stage:group.getValue()){
                if("TOTAL".equals(stage.function())){total=stage;continue;}
                String status=stage.missing()>0?"INCOMPLETE":stage.estimated()>0?"ESTIMATED":"CALCULATED";
                result.add(new HistoryEntry(stage.scope()+"_"+stage.function(),stage.scope(),stage.function(),stage.time(),stage.amount(),status));
            }
            if(total!=null)result.add(new HistoryEntry(total.scope()+"_TOTAL",total.scope(),"TOTAL",total.time(),total.amount(),total.missing()>0?"INCOMPLETE":total.estimated()>0?"ESTIMATED":"CALCULATED"));
        }
        return result;
    }
    public Summary summary(String scope){
        List<Entry> entries=jdbc.query("SELECT * FROM model_call_cost WHERE scope_key=? ORDER BY id",(r,n)->new Entry(
            r.getLong("id"),r.getString("category"),r.getString("model"),r.getObject("input_tokens",Long.class),r.getObject("cached_tokens",Long.class),
            r.getObject("audio_tokens",Long.class),r.getObject("output_tokens",Long.class),r.getBigDecimal("audio_seconds"),r.getBigDecimal("cost_usd"),
            r.getString("status"),r.getString("basis"),r.getString("pricing_json"),r.getString("created_at")),scope);
        BigDecimal total=BigDecimal.ZERO,diarization=BigDecimal.ZERO,roles=BigDecimal.ZERO;
        for(var e:entries){if(e.costUsd()==null)continue;total=total.add(e.costUsd());
            if("DIARIZATION".equals(e.category()))diarization=diarization.add(e.costUsd());
            if("ROLE_RECOGNITION".equals(e.category()))roles=roles.add(e.costUsd());}
        return new Summary(total,total.subtract(diarization).subtract(roles),diarization,roles,
            entries.stream().filter(e->e.costUsd()==null).count(),entries.stream().filter(e->"ESTIMATED".equals(e.status())).count(),entries);
    }
}
