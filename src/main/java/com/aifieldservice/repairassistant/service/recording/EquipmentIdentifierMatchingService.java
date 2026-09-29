package com.aifieldservice.repairassistant.service.recording;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import com.aifieldservice.repairassistant.dao.knowledge.EquipmentIdentifierMapper;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway;
import tools.jackson.databind.ObjectMapper;

/** Deterministic identifier correction first; the model is only a bounded, confirmable fallback. */
@Service
public class EquipmentIdentifierMatchingService {
    public record Candidate(String model, List<String> errorCodes) {}
    public record ScoredCandidate(String value, String model, double fuzzyScore, double finalScore) {}
    public record Decision(int itemIndex, String status, String originalValue, String effectiveValue,
            String suggestedValue, String model, String sourceText, double score, double margin,
            String reason, List<ScoredCandidate> candidates) {}
    public record MatchResult(List<OpenAiRecordingGateway.Extracted> items, List<Decision> decisions) {}
    private record Value(String value, String model) {}

    private static final Pattern MEASUREMENT = Pattern.compile("(?iu)\\d+(?:[.,]\\d+)?\\s*(?:℃|°C|度|分钟|分|小时|次|年|月|日)");
    private static final Pattern NEGATED = Pattern.compile("(?iu)(?:不是|没有|未显示|ではない|ありません|表示なし).{0,12}$");
    private static final String CODE_TOKEN = "[A-ZＥｅEイー]?\\s*[零〇一二三四五六七八九0-9]{1,3}(?:\\s*[、,，/・]\\s*[A-ZＥｅEイー]?\\s*[零〇一二三四五六七八九0-9]{1,3})*";
    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("[‘’'\"“”]\\s*("+CODE_TOKEN+")\\s*[‘’'\"“”]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTEXT_IDENTIFIER = Pattern.compile("(?iu)(?:错误码|故障码|代码|エラーコード|显示|表示)[^A-ZＥｅEイー零〇一二三四五六七八九0-9]{0,10}("+CODE_TOKEN+")");
    private static final Pattern SHORT_IDENTIFIER = Pattern.compile("(?iu)^"+CODE_TOKEN+"$");
    private static final Pattern QUOTED_MODEL = Pattern.compile("[‘’'\"“”]\\s*([A-Za-z0-9][A-Za-z0-9 _-]{1,40})\\s*[‘’'\"“”]");
    private static final Pattern MODEL_TOKEN = Pattern.compile("(?i)(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9-]{2,})(?![A-Za-z0-9])");
    private final EquipmentIdentifierMapper mapper;
    private final OpenAiRecordingGateway gateway;
    private final ObjectMapper json;

    public EquipmentIdentifierMatchingService(EquipmentIdentifierMapper mapper, OpenAiRecordingGateway gateway, ObjectMapper json) {
        this.mapper = mapper; this.gateway = gateway; this.json = json;
    }

    public MatchResult match(List<OpenAiRecordingGateway.Extracted> items, List<RecordingRows.Segment> segments) {
        if(items.stream().noneMatch(i->List.of("MODEL","ERROR_CODE").contains(i.type()))) return new MatchResult(items,List.of());
        var dictionary = dictionary();
        if (dictionary.isEmpty()) return unchanged(items, "NO_DICTIONARY", "知识库中没有可用的设备标识字典。");
        Map<String,RecordingRows.Segment> byKey = new HashMap<>(); segments.forEach(s -> byKey.put(s.segmentKey(),s));
        var output = new ArrayList<>(items); var decisions = new ArrayList<Decision>();
        Map<Long,String> confirmedModels = new HashMap<>();
        Map<Long,String> candidateModels = new HashMap<>();
        for (int i=0;i<items.size();i++) if ("MODEL".equals(items.get(i).type())) {
            var item=items.get(i); long file=evidenceFile(item,byKey);
            var scored=score(item,dictionary.stream().map(c->new Value(c.model(),c.model())).toList(),byKey,false,true);
            var decision=resolve(i,item,scored,dictionary,segments,output,file,confirmedModels);
            decisions.add(decision);
            String candidate="RULE_AUTO_CORRECTED".equals(decision.status())?decision.effectiveValue():decision.suggestedValue();
            if(candidate!=null&&!candidate.isBlank()) candidateModels.put(file,candidate);
        }
        for (int i=0;i<items.size();i++) if ("ERROR_CODE".equals(items.get(i).type())) {
            var item=items.get(i); long file=evidenceFile(item,byKey); String model=confirmedModels.getOrDefault(file,candidateModels.get(file));
            var values=dictionary.stream().filter(c->model==null||c.model().equals(model))
                    .flatMap(c->c.errorCodes().stream().map(code->new Value(code,c.model()))).distinct().toList();
            decisions.add(resolve(i,item,score(item,values,byKey,true,confirmedModels.containsKey(file)),dictionary,segments,output,file,confirmedModels));
        }
        for(var decision:decisions)if("ERROR_CODE".equals(items.get(decision.itemIndex()).type())&&!"RULE_AUTO_CORRECTED".equals(decision.status()))output.set(decision.itemIndex(),new OpenAiRecordingGateway.Extracted("ERROR_CODE",decision.sourceText(),items.get(decision.itemIndex()).evidenceSegmentIds()));
        synchronizeSymptoms(items,output,decisions);
        return new MatchResult(List.copyOf(output),List.copyOf(decisions));
    }

    private Decision resolve(int index, OpenAiRecordingGateway.Extracted item, List<ScoredCandidate> scored,
            List<Candidate> dictionary, List<RecordingRows.Segment> segments, List<OpenAiRecordingGateway.Extracted> output,
            long fileId, Map<Long,String> confirmedModels) {
        if(scored.isEmpty()) return decision(index,item,"UNRESOLVED",item.content(),null,null,0,0,"没有数据库候选。",scored);
        var first=scored.getFirst(); double margin=scored.size()==1?1:first.finalScore()-scored.get(1).finalScore();
        boolean related=!"ERROR_CODE".equals(item.type())||confirmedModels.containsKey(fileId);
        if(first.finalScore()>=.88&&margin>=.12&&related&&!measurementOrNegated(item,segments)) {
            output.set(index,new OpenAiRecordingGateway.Extracted(item.type(),first.value(),item.evidenceSegmentIds()));
            if("MODEL".equals(item.type())) confirmedModels.put(fileId,first.value());
            return decision(index,item,"RULE_AUTO_CORRECTED",first.value(),first.value(),first.model(),first.finalScore(),margin,"确定性规则得到唯一高置信度数据库候选。",scored);
        }
        try {
            var bounded=dictionary.stream().filter(c->scored.stream().anyMatch(s->s.model().equals(c.model()))).limit(5).toList();
            String source=identifierSource(item);
            var fallbackItem=new OpenAiRecordingGateway.Extracted(item.type(),source,item.evidenceSegmentIds());
            var suggested=gateway.matchIdentifiers(List.of(fallbackItem),necessarySegments(item,segments),bounded).getFirst();
            if(!suggested.content().equals(source)) {
                String model="MODEL".equals(item.type())?suggested.content():scored.stream().filter(s->s.value().equals(suggested.content())).map(ScoredCandidate::model).findFirst().orElse(null);
                return decision(index,item,"LLM_PENDING_CONFIRMATION",item.content(),suggested.content(),model,source,first.finalScore(),margin,"规则置信度不足，AI 在限定数据库候选中给出建议，需人工确认。",scored);
            }
        } catch(Exception ignored) { }
        return decision(index,item,"MANUAL_INPUT_REQUIRED",item.content(),null,first.model(),first.finalScore(),margin,"AI 无法确认标准值，请手动填写或明确保留提取值。",scored);
    }

    private List<ScoredCandidate> score(OpenAiRecordingGateway.Extracted item,List<Value> values,
            Map<String,RecordingRows.Segment> byKey,boolean errorCode,boolean related) {
        String displaySource=identifierSource(item);
        List<String> sources=new ArrayList<>();
        for(String part:displaySource.split("\\s*/\\s*"))if(!part.isBlank())sources.add(normalize(part));
        String joined=normalize(displaySource.replace("/",""));if(!joined.isBlank())sources.add(joined);
        boolean contextual=item.evidenceSegmentIds().stream().map(byKey::get).filter(Objects::nonNull).map(RecordingRows.Segment::originalText)
                .anyMatch(t->errorCode?t.matches("(?is).*(错误|故障|代码|エラー|コード|显示|表示).*"):t.matches("(?is).*(型号|型式|機種|model).*"));
        double evidence=item.evidenceSegmentIds().size()>1?1:.7;
        return values.stream().map(v->{String candidate=normalize(v.value());double fuzzy=sources.stream().mapToDouble(source->ratio(source,candidate)).max().orElse(0);double total=fuzzy*.5+(contextual?1:.7)*.2+(related?1:0)*.2+evidence*.1; return new ScoredCandidate(v.value(),v.model(),fuzzy,Math.min(1,total));})
                .sorted(Comparator.comparingDouble(ScoredCandidate::finalScore).reversed().thenComparing(ScoredCandidate::value)).limit(5).toList();
    }

    private boolean measurementOrNegated(OpenAiRecordingGateway.Extracted item,List<RecordingRows.Segment> segments) {
        String evidence=necessarySegments(item,segments).stream().map(RecordingRows.Segment::originalText).collect(java.util.stream.Collectors.joining(" "));
        return MEASUREMENT.matcher(item.content()).find()||NEGATED.matcher(evidence).find();
    }
    private List<RecordingRows.Segment> necessarySegments(OpenAiRecordingGateway.Extracted item,List<RecordingRows.Segment> segments) {
        var ids=new HashSet<>(item.evidenceSegmentIds()); return segments.stream().filter(s->ids.contains(s.segmentKey())).toList();
    }
    private void synchronizeSymptoms(List<OpenAiRecordingGateway.Extracted> original,List<OpenAiRecordingGateway.Extracted> output,List<Decision> decisions) {
        for(var correction:decisions) {
            if(!"RULE_AUTO_CORRECTED".equals(correction.status())||!"ERROR_CODE".equals(original.get(correction.itemIndex()).type())||correction.originalValue().equals(correction.effectiveValue())) continue;
            for(int i=0;i<original.size();i++) { var symptom=output.get(i); if(!"SYMPTOM".equals(symptom.type())) continue;
                int start=symptom.content().indexOf(correction.sourceText());
                if(start<0||symptom.content().indexOf(correction.sourceText(),start+correction.sourceText().length())>=0) continue;
                String after=symptom.content().substring(start+correction.sourceText().length()); if(after.startsWith("℃")||after.startsWith("°C")) continue;
                output.set(i,new OpenAiRecordingGateway.Extracted("SYMPTOM",symptom.content().substring(0,start)+correction.effectiveValue()+after,symptom.evidenceSegmentIds()));
            }
        }
    }
    private MatchResult unchanged(List<OpenAiRecordingGateway.Extracted> items,String status,String reason) {
        var decisions=new ArrayList<Decision>(); for(int i=0;i<items.size();i++) if(List.of("MODEL","ERROR_CODE").contains(items.get(i).type())) decisions.add(decision(i,items.get(i),status,items.get(i).content(),null,null,0,0,reason,List.of()));
        return new MatchResult(items,decisions);
    }
    private Decision decision(int index,OpenAiRecordingGateway.Extracted item,String status,String effective,String suggested,String model,double score,double margin,String reason,List<ScoredCandidate> candidates) {
        return decision(index,item,status,effective,suggested,model,identifierSource(item),score,margin,reason,candidates);
    }
    private Decision decision(int index,OpenAiRecordingGateway.Extracted item,String status,String effective,String suggested,String model,String sourceText,double score,double margin,String reason,List<ScoredCandidate> candidates) {
        return new Decision(index,status,item.content(),effective,suggested,model,sourceText,score,margin,reason,candidates);
    }
    public static String identifierSource(OpenAiRecordingGateway.Extracted item) {
        String content=item.content()==null?"":item.content().strip();
        if("MODEL".equals(item.type())) {
            var quoted=new ArrayList<String>(); var quotedMatcher=QUOTED_MODEL.matcher(content);
            while(quotedMatcher.find()) quoted.add(quotedMatcher.group(1).strip());
            if(!quoted.isEmpty()) return String.join(" / ",new LinkedHashSet<>(quoted));
            var tokens=new ArrayList<String>(); var tokenMatcher=MODEL_TOKEN.matcher(content);
            while(tokenMatcher.find()&&tokens.size()<3) tokens.add(tokenMatcher.group(1));
            return tokens.isEmpty()?content:String.join(" / ",new LinkedHashSet<>(tokens));
        }
        if(!"ERROR_CODE".equals(item.type())) return content;
        for(Pattern pattern:List.of(QUOTED_IDENTIFIER,CONTEXT_IDENTIFIER)) { var matcher=pattern.matcher(content); if(matcher.find()) return matcher.group(1).strip(); }
        return SHORT_IDENTIFIER.matcher(content).matches()?content:content;
    }
    private List<Candidate> dictionary() {
        var byModel=new TreeMap<String,TreeSet<String>>();
        for(var row:mapper.listIdentifiers()) { if(row.model()==null||row.model().isBlank()) continue; var codes=byModel.computeIfAbsent(row.model(),ignored->new TreeSet<>()); if(row.errorCodesJson()==null||row.errorCodesJson().isBlank()) continue; var values=json.readTree(row.errorCodesJson()); if(!values.isArray()) throw new IllegalStateException("知识库错误码格式无效。"); for(var value:values) if(value.isString()&&!value.asText().isBlank()) codes.add(value.asText()); }
        var result=new ArrayList<Candidate>(); byModel.forEach((model,codes)->result.add(new Candidate(model,List.copyOf(codes)))); return result;
    }
    private long evidenceFile(OpenAiRecordingGateway.Extracted item,Map<String,RecordingRows.Segment> byKey) { return item.evidenceSegmentIds().stream().map(byKey::get).filter(Objects::nonNull).mapToLong(RecordingRows.Segment::recordingFileId).findFirst().orElse(-1); }

    static String normalize(String raw) {
        String value=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s‐‑‒–—―ーｰ_-]+","");
        return value.replace("零","0").replace("〇","0").replace("一","1").replace("二","2").replace("三","3").replace("四","4").replace("五","5").replace("六","6").replace("七","7").replace("八","8").replace("九","9").replace("イー","E").replace("イ","E");
    }
    /** RapidFuzz-compatible normalized Levenshtein ratio for short ordered identifiers. */
    static double ratio(String left,String right) {
        if(left.equals(right))return 1;if(left.isEmpty()||right.isEmpty())return 0;int[] previous=new int[right.length()+1];for(int j=0;j<=right.length();j++)previous[j]=j;
        for(int i=1;i<=left.length();i++){int[] current=new int[right.length()+1];current[0]=i;for(int j=1;j<=right.length();j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),previous[j-1]+(left.charAt(i-1)==right.charAt(j-1)?0:1));previous=current;}
        return 1d-(double)previous[right.length()]/Math.max(left.length(),right.length());
    }
}
