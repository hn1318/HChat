package com.hchat.server.plugin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.plugin.ai.AiModels.AiChatInput;
import com.hchat.server.plugin.ai.AiModels.AiChatResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议实现（/chat/completions）。兼容 OpenAI、DeepSeek、Moonshot、
 * Ollama、vLLM 等一切同协议服务。
 */
@Component
public class OpenAiCompatibleProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private final HChatProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public OpenAiCompatibleProvider(HChatProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public AiChatResult chat(AiChatInput input) {
        ObjectNode body = buildBody(input, false);
        HttpResponse<String> response = send(body, HttpResponse.BodyHandlers.ofString());
        try {
            JsonNode node = mapper.readTree(response.body());
            String content = node.path("choices").path(0).path("message").path("content").asText("");
            Map<String, Object> usage = readUsage(node.path("usage"));
            return new AiChatResult(content, usage);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "解析模型响应失败: " + e.getMessage());
        }
    }

    @Override
    public AiChatResult stream(AiChatInput input, Consumer<String> onDelta) {
        ObjectNode body = buildBody(input, true);
        HttpResponse<Stream<String>> response = send(body, HttpResponse.BodyHandlers.ofLines());
        StringBuilder full = new StringBuilder();
        Map<String, Object> usage = new LinkedHashMap<>();
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line == null || line.isBlank() || !line.startsWith("data:")) {
                    return;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    return;
                }
                try {
                    JsonNode chunk = mapper.readTree(payload);
                    if (chunk.hasNonNull("usage")) {
                        usage.putAll(readUsage(chunk.path("usage")));
                    }
                    JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String text = delta.asText("");
                        if (!text.isEmpty()) {
                            full.append(text);
                            onDelta.accept(text);
                        }
                    }
                } catch (Exception e) {
                    log.debug("跳过无法解析的流式分片: {}", e.getMessage());
                }
            });
        }
        if (full.isEmpty()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "模型没有返回内容");
        }
        return new AiChatResult(full.toString(), usage);
    }

    private ObjectNode buildBody(AiChatInput input, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", input.model() == null ? props.getAi().getModel() : input.model());
        body.put("stream", stream);
        body.put("temperature", input.temperature());
        body.put("max_tokens", input.maxTokens());
        ArrayNode messages = body.putArray("messages");
        if (input.systemPrompt() != null && !input.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", input.systemPrompt());
        }
        for (AiModels.AiMessage message : input.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            node.put("content", message.content() == null ? "" : message.content());
        }
        return body;
    }

    private <T> HttpResponse<T> send(ObjectNode body, HttpResponse.BodyHandler<T> handler) {
        String baseUrl = props.getAi().getBaseUrl().replaceAll("/+$", "");
        String apiKey = props.getAi().getApiKey();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, props.getAi().getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<T> response = client.send(builder.build(), handler);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body() instanceof String s ? s : "";
                throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                        "模型服务返回 " + response.statusCode() + (detail.isBlank() ? "" : ": " + detail));
            }
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "模型调用被中断");
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "模型调用失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readUsage(JsonNode usage) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return result;
        }
        usage.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asLong()));
        return result;
    }
}
