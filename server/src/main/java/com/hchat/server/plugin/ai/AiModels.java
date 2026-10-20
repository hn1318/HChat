package com.hchat.server.plugin.ai;

import java.util.List;
import java.util.Map;

/** AI 插件的数据契约（与具体厂商无关）。 */
public final class AiModels {

    private AiModels() {}

    public record AiMessage(String role, String content) {}

    public record AiChatInput(
            String model,
            String systemPrompt,
            List<AiMessage> messages,
            double temperature,
            int maxTokens) {}

    public record AiChatResult(String content, Map<String, Object> usage) {}
}
