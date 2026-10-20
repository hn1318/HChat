package com.hchat.server.plugin.ai;

import com.hchat.server.plugin.ai.AiModels.AiChatInput;
import com.hchat.server.plugin.ai.AiModels.AiChatResult;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 演示用 Provider：无需任何 Key，按固定节奏流式返回一段可读的回答。
 * 用途：本地体验 / CI 冒烟测试 / 未配置模型厂商时的安全兜底。
 */
@Component
public class MockAiProvider implements AIProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public AiChatResult chat(AiChatInput input) {
        String content = buildReply(input);
        return new AiChatResult(content, Map.of("promptTokens", 0, "completionTokens", 0, "provider", "mock"));
    }

    @Override
    public AiChatResult stream(AiChatInput input, Consumer<String> onDelta) {
        String reply = buildReply(input);
        StringBuilder sent = new StringBuilder();
        int chunkSize = 6;
        for (int i = 0; i < reply.length(); i += chunkSize) {
            String chunk = reply.substring(i, Math.min(reply.length(), i + chunkSize));
            onDelta.accept(chunk);
            sent.append(chunk);
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new AiChatResult(sent.toString(), Map.of("promptTokens", 0, "completionTokens", 0, "provider", "mock"));
    }

    private String buildReply(AiChatInput input) {
        List<AiModels.AiMessage> messages = input.messages();
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        String prompt = input.systemPrompt() == null ? "" : input.systemPrompt();
        return "（Mock AI）已收到你的消息：" + shorten(last) + "。"
                + (prompt.isBlank() ? "" : " 当前人格设定：" + shorten(prompt) + "。")
                + " 这是 HChat 的内置演示回复，把 AI_PROVIDER 切换为 openai-compatible 并配置 AI_API_KEY 即可接入真实大模型。";
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "...";
    }
}
