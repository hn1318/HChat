package com.hchat.server.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** 文件、AI、Webhook 相关 DTO。 */
public final class ResourceDtos {

    private ResourceDtos() {}

    public record UploadResult(
            String id,
            String url,
            String fileName,
            String mimeType,
            long size) {}

    public record CreateAgent(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String provider,
            @Size(max = 128) String model,
            @Size(max = 8000) String systemPrompt,
            Map<String, Object> config) {}

    public record AgentView(
            String id,
            String name,
            String provider,
            String model,
            String systemPrompt,
            String status) {}

    public record AiStreamRequest(
            Long agentId,
            @NotBlank String text,
            @Size(max = 64) String clientMsgId,
            Boolean persistUserMessage) {}

    public record CreateWebhook(
            @NotBlank @Size(max = 1024) String url,
            @NotEmpty List<String> events,
            @Size(max = 128) String secret) {}

    public record WebhookView(
            String id,
            String url,
            List<String> events,
            String status,
            String createdAt) {}
}
