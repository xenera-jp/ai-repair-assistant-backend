package com.aifieldservice.repairassistant.service.recording;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
import com.aifieldservice.repairassistant.dao.knowledge.EquipmentIdentifierMapper;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;
import tools.jackson.databind.ObjectMapper;

/** Pure rule-based, local identifier patches; diarization remains authoritative for all other text. */
@Service
public class TranscriptIdentifierProtector {
    public record Replacement(String field,String sourceId,long sourceStart,long sourceEnd,String targetId,
            long targetStart,long targetEnd,String originalValue,String realtimeValue,String value,String model) {}
    public record RuntimeCandidate(String field,String value,String realtimeValue,String sourceId,long startMs,long endMs) {}
    public record Result(List<Transcript> segments,List<Replacement> replacements,List<RuntimeCandidate> candidates) {}
    private record Token(String field,int start,int end,String raw,String value) {}
    private record Mention(Transcript source,Token token) {}
    private static final Pattern MODEL=Pattern.compile("(?i)(?<![A-Za-z0-9])(?:[A-Z](?:[ _-]*[A-Z]){1,5}[ _-]*(?:one|two|three|four|ワン)[ _-]*(?:[A-Z][ _-]*){2,6}|(?:[A-Z][ _-]+){2,5}[0-9]{1,5}(?:[ _-]+[A-Z]){2,6}|[A-Z]{1,6}[ _-]?[0-9]{1,5}(?:[ _-]?[A-Z0-9]{1,10})?|[A-Z]{2,6}[-_][A-Z0-9]{2,10}|[A-Z]{3,16})(?![A-Za-z0-9])");
    private static final Pattern CODE=Pattern.compile("(?i)(?<![A-Za-z0-9])(?:イーチ|イーイチ|(?:[A-Z]{1,3}|イー)?[ ・·._-]*(?:[0-9零〇一二三四五六七八九]{1,3}|one|two|three|four|イチ|いち)|CF)(?![A-Za-z0-9])");
    private static final Pattern CONTEXT=Pattern.compile("(?i)型号|机型|型式|型番|機種|model|错误码|故障码|エラー(?:コード)?|error(?: code)?|alarm|コード|表示|点滅|显示|製造番号|序列号|シリアル|serial|温度|temperature|電話|TEL");
    private static final Pattern CONFLICT=Pattern.compile("ではない|ではなく|じゃなく|不是|不对|訂正|更正|いいえ|違います");
    private final EquipmentIdentifierMapper mapper;
    private final ObjectMapper json=new ObjectMapper();
    public TranscriptIdentifierProtector(EquipmentIdentifierMapper mapper) { this.mapper=mapper; }
    public ProtectionState newSession() {
        var models=new TreeMap<String,String>(); var codes=new TreeMap<String,Map<String,String>>();
        for(var row:mapper.listIdentifiers()) {
            if(row.model()==null || row.model().isBlank()) continue;
            String model=row.model().strip(); models.put(normalize(model),model);
            var values=codes.computeIfAbsent(model,k -> new TreeMap<>());
            if(row.errorCodesJson()!=null && !row.errorCodesJson().isBlank()) {
                var array=json.readTree(row.errorCodesJson());
                if(!array.isArray()) throw new IllegalStateException("设备错误码字典格式无效。");
                for(var code:array) if(code.isString() && !code.asText().isBlank()) values.put(normalize(code.asText()),code.asText());
            }
        }
        return new ProtectionState(models,codes);
    }
    public static final class ProtectionState {
        private final Map<String,String> models;
        private final Map<String,Map<String,String>> codes;
        private final Map<Transcript,List<Token>> parsed=new HashMap<>();
        private ProtectionState(Map<String,String> models,Map<String,Map<String,String>> codes) { this.models=models; this.codes=codes; }
        public Result protect(List<Transcript> diarized,List<Transcript> realtime) {
            var mentions=new ArrayList<Mention>();
            for(var source:realtime) {
                if(CONFLICT.matcher(source.text()).find()) continue;
                for(var token:parsed.computeIfAbsent(source,s -> tokens(s.text()))) {
                    if(token.field().equals("MODEL") && models.containsKey(token.value())) mentions.add(new Mention(source,token));
                    if(token.field().equals("ERROR_CODE") && codes.values().stream().anyMatch(c -> c.containsKey(token.value()))) mentions.add(new Mention(source,token));
                }
            }
            var output=new ArrayList<Transcript>(); var audit=new ArrayList<Replacement>();
            for(var target:diarized) {
                if(CONFLICT.matcher(target.text()).find()) { output.add(target); continue; }
                String text=target.text(); var edits=new ArrayList<Replacement>(); var slots=tokens(text);
                for(var slot:slots.reversed()) {
                    var candidates=mentions.stream().filter(m -> m.token().field().equals(slot.field())
                            && m.source().startMs()<target.endMs() && target.startMs()<m.source().endMs()).toList();
                    var values=candidates.stream().map(m -> m.token().value()).distinct().toList();
                    if(values.size()!=1) continue;
                    var candidate=candidates.getFirst(); String value=values.getFirst(),model=null;
                    String replacement;
                    if(slot.field().equals("MODEL")) {
                        model=models.get(value);
                        // Repeated mentions are safe only when every model slot agrees with
                        // the single realtime candidate. Keep genuinely conflicting devices intact.
                        if(slots.stream().filter(s -> s.field().equals("MODEL")).count()>1 && slots.stream().filter(s -> s.field().equals("MODEL")).anyMatch(s ->
                                models.containsKey(s.value()) ? !s.value().equals(value)
                                        : !similarModel(s.value(),value,models.keySet()))) continue;
                        if(slot.value().equals(value) ? slot.raw().equals(model)
                                : !models.containsKey(slot.value()) && !similarModel(slot.value(),value,models.keySet())) continue;
                        replacement=model;
                    } else {
                        var precedingModels=mentions.stream().filter(m -> m.token().field().equals("MODEL")
                                && (m.source().startMs()<candidate.source().startMs()
                                    || m.source().providerId().equals(candidate.source().providerId()) && m.token().start()<candidate.token().start()))
                                .map(m -> models.get(m.token().value())).distinct().toList();
                        if(precedingModels.size()>1) continue;
                        if(realtime.stream().anyMatch(s -> s.startMs()<=candidate.source().startMs()
                                && CONFLICT.matcher(s.text()).find())) continue;
                        model=precedingModels.isEmpty()?null:precedingModels.getFirst();
                        // An explicit error-code field does not need a model in the same
                        // conversation. Validate against the shared dictionary until one is known.
                        replacement=model==null ? codes.values().stream().map(c -> c.get(value))
                                .filter(Objects::nonNull).findFirst().orElse(null)
                                : codes.getOrDefault(model,Map.of()).get(value);
                        if(replacement==null || slot.raw().equals(replacement)) continue;
                    }
                    edits.add(new Replacement(slot.field(),candidate.source().providerId(),candidate.source().startMs(),candidate.source().endMs(),
                            target.providerId(),target.startMs(),target.endMs(),slot.raw(),candidate.token().raw(),replacement,model));
                    text=text.substring(0,slot.start())+replacement+text.substring(slot.end());

                }
                // Collapse only adjacent identifier repetitions backed by a unique
                // realtime value. Do not deduplicate arbitrary diarization prose.
                for(var field:List.of("MODEL","ERROR_CODE")) {
                    var values=mentions.stream().filter(m -> m.token().field().equals(field)
                            && m.source().startMs()<target.endMs() && target.startMs()<m.source().endMs())
                            .map(m -> m.token().value()).distinct().toList();
                    if(values.size()!=1) continue;
                    String literal=field.equals("MODEL")?models.get(values.getFirst()):values.getFirst();
                    text=collapseIdentifierRepeat(text,literal,field);
                }
                // Apply edits backwards so original character offsets remain valid.
                output.add(new Transcript(target.providerId(),target.startMs(),target.endMs(),target.speaker(),text)); audit.addAll(edits);
            }
            var candidates=mentions.stream().map(m -> new RuntimeCandidate(m.token().field(),
                    m.token().field().equals("MODEL")?models.get(m.token().value()):m.token().value(),m.token().raw(),
                    m.source().providerId(),m.source().startMs(),m.source().endMs())).toList();
            return new Result(List.copyOf(output),List.copyOf(audit),candidates);
        }
    }
    private static String collapseIdentifierRepeat(String text,String literal,String field) {
        String q=Pattern.quote(literal);
        if(field.equals("MODEL")) {
            String label="(?:機種|型式|型番|型号|机型)(?:が|は|是|为)?\\s*";
            return text.replaceAll("("+label+q+")[ \u3000]+(?="+label+q+")","");
        }
        // This phrase is duplicated in the reported error-code row; retain its first occurrence.
        String phrase=q+"というのが点滅しています";
        return text.replaceAll("("+phrase+")[ \u3000]*(?:"+phrase+")","$1");
    }
    private static boolean similarModel(String target,String candidate,Set<String> dictionary) {
        if(target.length()<3) return false;
        if(candidate.startsWith(target)) return true;
        double score=similarity(target,candidate);
        return score>=0.75 && dictionary.stream().filter(v -> !v.equals(candidate)).noneMatch(v -> similarity(target,v)>=score);
    }
    private static double similarity(String a,String b) {
        int[] previous=new int[b.length()+1]; for(int j=0;j<=b.length();j++) previous[j]=j;
        for(int i=1;i<=a.length();i++) {
            int[] next=new int[b.length()+1]; next[0]=i;
            for(int j=1;j<=b.length();j++) next[j]=Math.min(Math.min(next[j-1]+1,previous[j]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            previous=next;
        }
        return 1.0-previous[b.length()]/(double)Math.max(a.length(),b.length());
    }
    private static List<Token> tokens(String original) {
        // Normalize fullwidth literals without changing replacement offsets.
        // Keep expanding characters (e.g. ℃ -> °C) intact instead of skipping the whole row.
        var normalized=new StringBuilder(original.length());
        for(int i=0;i<original.length();i++) {
            String raw=original.substring(i,i+1);
            String folded=Normalizer.normalize(raw,Normalizer.Form.NFKC);
            normalized.append(folded.length()==1?folded:raw);
        }
        String text=normalized.toString();
        var result=new ArrayList<Token>();
        for(var entry:Map.of("MODEL",MODEL,"ERROR_CODE",CODE).entrySet()) {
            var matcher=entry.getValue().matcher(text);
            while(matcher.find()) {
                if(result.stream().anyMatch(t -> t.field().equals(entry.getKey()) && t.end()>matcher.start())) continue;
                if(entry.getKey().equals("ERROR_CODE") && text.substring(matcher.end(),Math.min(text.length(),matcher.end()+8))
                        .matches("(?i)^\\s*(?:℃|°C|度|分|秒|V|Hz|A|%).*")) continue;
                int end=matcher.end(); String raw=matcher.group();
                if(entry.getKey().equals("MODEL")) {
                    if(raw.chars().noneMatch(Character::isDigit) && !raw.equals(raw.toUpperCase(Locale.ROOT))
                            && !raw.matches("(?i).*\\b(?:one|two|three|four)\\b.*")) continue;
                    while(!raw.isEmpty() && Character.isWhitespace(raw.charAt(raw.length()-1))) { raw=raw.substring(0,raw.length()-1); end--; }
                    var filler=Pattern.compile("(?i)\\s+(?:is|was|are|and|with|has)$").matcher(raw);
                    if(filler.find()) { end=matcher.start()+filler.start(); raw=raw.substring(0,filler.start()); }
                    // Include a spoken numeric suffix in the replacement span.
                    if(text.startsWith("ワン",end)) { end+=2; raw+="1"; }
                    // A repeated spelling such as "RIR-1 r i r1ssp" is one identifier span.
                    var spelling=Pattern.compile("^([A-Za-z]{2,6}[-_ ]?[0-9]+)(\\s+(?:[A-Za-z]\\s+){2,}[A-Za-z0-9-]+)").matcher(text.substring(matcher.start()));
                    if(spelling.find() && normalize(spelling.group(2)).startsWith(normalize(spelling.group(1)).replaceAll("[0-9].*$",""))) {
                        raw=spelling.group(2); end=matcher.start()+spelling.end();
                    }
                }
                String prefix=text.substring(Math.max(0,matcher.start()-40),matcher.start());
                var contexts=CONTEXT.matcher(prefix); String last=""; while(contexts.find()) last=contexts.group();
                boolean model=last.matches("(?i)型号|机型|型式|型番|機種|model");
                // Unlabelled technical restatements still require dictionary-validated,
                // overlapping realtime evidence before any replacement can happen.
                if(last.isEmpty() && normalize(raw).matches("[A-Z]{2,6}[0-9][A-Z0-9]*")) model=true;
                boolean code=last.matches("(?i)错误码|故障码|エラー(?:コード)?|error(?: code)?|alarm|コード|表示|点滅|显示");
                // E-prefixed codes / CF are technical literals even in a short answer without a label.
                boolean forbidden=last.matches("(?i)製造番号|序列号|シリアル|serial|温度|temperature|電話|TEL");
                String suffix=text.substring(end,Math.min(text.length(),end+40));
                if(!forbidden && (last.isEmpty() && normalize(raw).matches("E[0-9]{1,3}|CF")
                        || suffix.matches("^\\s*(?:という(?:の)?)?(?:が|は|で)?(?:点滅|エラー|错误码|故障码).*"))) code=true;
                if(entry.getKey().equals("MODEL") && model || entry.getKey().equals("ERROR_CODE") && code)
                    result.add(new Token(entry.getKey(),matcher.start(),end,original.substring(matcher.start(),end),normalize(raw)));
            }
        }
        result.sort(Comparator.comparingInt(Token::start)); return List.copyOf(result);
    }
    private static String normalize(String text) {
        String value=Normalizer.normalize(text,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replace("イーチ","E1").replace("イーイチ","E1").replace("イー","E")
                .replaceAll("\\bONE\\b","1").replaceAll("\\bTWO\\b","2")
                .replaceAll("\\bTHREE\\b","3").replaceAll("\\bFOUR\\b","4")
                .replace("ワン","1").replace("イチ","1").replace("いち","1");
        String digits="零〇一二三四五六七八九";
        for(int i=0;i<digits.length();i++) value=value.replace(digits.charAt(i),(char)('0'+Math.max(0,i-1)));
        return value.replaceAll("[^A-Z0-9]","");
    }
}
