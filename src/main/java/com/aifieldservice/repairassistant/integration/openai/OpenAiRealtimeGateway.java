package com.aifieldservice.repairassistant.integration.openai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Server-side connection only; no API credentials are sent to the browser. */
@Component
public class OpenAiRealtimeGateway {
    public interface Connection extends AutoCloseable {
        void send(Map<String, ?> event);
        @Override void close();
    }
    private final RepairAssistantProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiRealtimeGateway(RepairAssistantProperties properties) { this.properties = properties; }

    public Map<String, Object> configuration(String language) {
        var transcription = new java.util.HashMap<String, Object>();
        transcription.put("model", properties.recording().transcriptionModel());
        transcription.put("delay", "low");
        if ("ja-JP".equals(language)) transcription.put("languages", List.of("ja"));
        if ("zh-CN".equals(language)) transcription.put("languages", List.of("zh-cn"));
        var input = new java.util.HashMap<String, Object>();
        input.put("format", Map.of("type", "audio/pcm", "rate", 24000));
        input.put("transcription", transcription);
        input.put("turn_detection", null);
        return Map.of("type", "session.update", "session", Map.of("type", "transcription", "audio", Map.of("input", input)));
    }

    public Connection connect(String language, Consumer<JsonNode> event, Consumer<String> failure) {
        String key = properties.openai().apiKey();
        if (key == null || key.isBlank()) throw new IllegalStateException("OpenAI API Key 未配置。");
        URI base = URI.create(properties.openai().baseUrl());
        String path = base.getPath().replaceAll("/+$", "") + "/realtime";
        URI uri;
        try { uri = new URI("http".equals(base.getScheme()) ? "ws" : "wss", null, base.getHost(), base.getPort(), path, "intent=transcription", null); }
        catch (Exception e) { throw new IllegalStateException("实时转写服务地址无效。", e); }
        int timeout = Math.max(1, properties.recording().connectTimeoutSeconds());
        CompletableFuture<Void> configured = new CompletableFuture<>();
        var listener = new WebSocket.Listener() {
            private final StringBuilder message = new StringBuilder();
            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                message.append(data);
                if (message.length() > 2_000_000) { socket.abort(); onError(socket, new IllegalStateException("服务消息过大。")); return null; }
                if (last) {
                    try {
                        JsonNode node = json.readTree(message.toString()); message.setLength(0);
                        String type = node.path("type").asText();
                        if ("session.updated".equals(type) || "transcription_session.updated".equals(type)) configured.complete(null);
                        if ("error".equals(type)) {
                            String detail = "实时转写服务错误：" + node.path("error").path("message").asText("请求失败");
                            configured.completeExceptionally(new IllegalStateException(detail)); failure.accept(detail);
                        } else event.accept(node);
                    } catch (Exception e) { configured.completeExceptionally(e); failure.accept("实时转写消息处理失败。"); }
                }
                socket.request(1); return null;
            }
            @Override public void onError(WebSocket socket, Throwable error) {
                configured.completeExceptionally(error); failure.accept("实时转写连接中断，请重新演示。");
            }
            @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                configured.completeExceptionally(new IllegalStateException("连接关闭。"));
                failure.accept("实时转写连接已关闭，请重新演示。"); return null;
            }
        };
        WebSocket socket = null;
        try {
            socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).build().newWebSocketBuilder()
                    .header("Authorization", "Bearer " + key).connectTimeout(Duration.ofSeconds(timeout))
                    .buildAsync(uri, listener).get(timeout, TimeUnit.SECONDS);
            WebSocket connected = socket;
            Connection connection = new Connection() {
                @Override public void send(Map<String, ?> payload) {
                    connected.sendText(json.writeValueAsString(payload), true).orTimeout(timeout, TimeUnit.SECONDS).join();
                }
                @Override public void close() { connected.abort(); }
            };
            connection.send(configuration(language));
            configured.get(timeout, TimeUnit.SECONDS);
            return connection;
        } catch (Exception error) {
            if (socket != null) socket.abort();
            // Do not expose authentication headers or arbitrary handshake response bodies.
            throw new IllegalStateException("无法建立实时转写会话，请检查模型配置、服务地址与账号权限。", error);
        }
    }
}
