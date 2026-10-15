package com.aifieldservice.repairassistant.integration.moss;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;
import tools.jackson.databind.ObjectMapper;

/** Streaming transcription for uploaded recordings. Speaker labels belong to one request. */
@Component
public class MossRecordingGateway {
    private final RestClient client;
    private final String model;
    // Domain vocabulary shared by every MOSS transcription request.
    private static final List<String> KEYTERMS = List.of(
            "FH1-AAC", "FH1-SSB", "RIR1-SSB", "HNC-120AA",
            "E1", "E2", "E3", "E4", "E6", "E7", "E8", "E9", "E10", "CF");
    private final ObjectMapper json;

    public MossRecordingGateway(RestClient.Builder builder, ObjectMapper json,
            @Value("${repair-assistant.moss.base-url:https://api.mosi.cn/v1}") String baseUrl,
            @Value("${repair-assistant.moss.api-key:}") String key,
            @Value("${repair-assistant.moss.transcription-model:moss-transcribe-diarize-pro}") String model,
            @Value("${repair-assistant.recording.connect-timeout-seconds:15}") int connectTimeout,
            @Value("${repair-assistant.recording.read-timeout-seconds:180}") int readTimeout) {
        this.json=json; this.model=model;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1,connectTimeout)));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1,readTimeout)));
        client=key.isBlank() ? null : builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization","Bearer "+key).build();
    }

    public void validate() {
        if(client==null) throw new IllegalStateException("MOSS API Key 未配置。");
        if(model.isBlank()) throw new IllegalStateException("MOSS 模型未配置。");

    }

    public List<Transcript> diarize(byte[] wav, Consumer<String> delta) {
        return request(new ByteArrayResource(wav) { @Override public String getFilename() { return "played-window.wav"; } },
                (wav.length-44L)*1000/48000,delta,segment -> {});
    }

    public List<Transcript> transcribe(java.nio.file.Path path,String filename,Consumer<Transcript> segment) {
        return request(new org.springframework.core.io.FileSystemResource(path) {
            @Override public String getFilename() { return filename; }
        },60*60*1000L,chunk -> {},segment);
    }

    private List<Transcript> request(org.springframework.core.io.Resource audio,long duration,Consumer<String> delta,
            Consumer<Transcript> segmentSink) {
        validate();
        var parts=new LinkedMultiValueMap<String,Object>();
        parts.add("model",model);
        parts.add("file",audio);
        parts.add("diarize","true"); parts.add("stream","true"); parts.add("response_format","verbose_json");
        parts.add("keyterms",json.writeValueAsString(KEYTERMS));
        return client.post().uri("/audio/transcriptions").contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.TEXT_EVENT_STREAM).body(parts).exchange((request,response) -> {
                    if(!response.getStatusCode().is2xxSuccessful())
                        throw new IllegalStateException("MOSS 转写请求失败（HTTP "+response.getStatusCode().value()+"）。");
                    var segments=new ArrayList<Transcript>();
                    var text=new StringBuilder();
                    var data=new StringBuilder();
                    boolean done=false;
                    boolean hasText=false;
                    try(var reader=new BufferedReader(new InputStreamReader(response.getBody(),StandardCharsets.UTF_8))) {
                        String line;
                        while(true) {
                            line=reader.readLine();
                            boolean eof=line==null;
                            if(eof && data.isEmpty()) break;
                            if(eof) line="";
                            if(line.startsWith("data:")) {
                                if(!data.isEmpty()) data.append('\n');
                                data.append(line.substring(5).stripLeading());
                                if(data.length()>1_000_000) throw new IllegalStateException("MOSS 事件过大。");
                            }
                            if(!line.isEmpty()) continue;
                            if(data.isEmpty()) continue;
                            if(data.toString().equals("[DONE]")) { data.setLength(0); if(eof) break; else continue; }
                            var event=json.readTree(data.toString()); data.setLength(0);
                            switch(event.path("type").asText()) {
                                case "transcript.text.delta" -> {
                                    text.append(event.path("delta").asText());
                                    if(text.length()>1_000_000) throw new IllegalStateException("MOSS 转写文本过长。");
                                    String chunk=event.path("delta").asText();
                                    if(!chunk.isEmpty()) delta.accept(chunk);
                                }
                                case "transcript.segment.done" -> {
                                    double startSeconds=event.path("start").asDouble(-1), endSeconds=event.path("end").asDouble(-1);
                                    long start=Math.round(startSeconds*1000), end=Math.round(endSeconds*1000);
                                    if(!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)
                                            || start<0 || end<start || end>duration+1000)
                                        throw new IllegalStateException("MOSS 分离时间范围无效。");
                                    String value=event.path("text").asText().strip();
                                    String speaker=event.path("speaker").asText("UNKNOWN");
                                    if(!value.isBlank()) {
                                        var segment=new Transcript("moss_"+segments.size(),Math.min(start,duration),
                                                Math.min(end,duration),speaker.isBlank()?"UNKNOWN":speaker,value);
                                        segments.add(segment); segmentSink.accept(segment);
                                    }
                                }
                                case "transcript.text.done" -> { done=true; hasText=!event.path("text").asText().isBlank(); }
                                case "error" -> throw new IllegalStateException("MOSS 流式转写失败。");
                                default -> { }
                            }
                            if(eof) break;
                        }
                    }
                    if(segments.isEmpty() && (hasText || !text.isEmpty())) throw new IllegalStateException("MOSS 未返回说话人分段。");
                    if(!done) throw new IllegalStateException("MOSS 转写流提前结束。");
                    return segments.stream().sorted(Comparator.comparingLong(Transcript::startMs)).toList();
                });
    }
}
