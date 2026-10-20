package com.hchat.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.api.dto.ResourceDtos.AgentView;
import com.hchat.server.api.dto.ResourceDtos.AiStreamRequest;
import com.hchat.server.api.dto.ResourceDtos.CreateAgent;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.AiAgent;
import com.hchat.server.domain.AiRun;
import com.hchat.server.domain.Conversation;
import com.hchat.server.domain.Message;
import com.hchat.server.mapper.AiAgentMapper;
import com.hchat.server.mapper.AiRunMapper;
import com.hchat.server.mapper.MessageMapper;
import com.hchat.server.plugin.ai.AIProvider;
import com.hchat.server.plugin.ai.AIProviderRegistry;
import com.hchat.server.plugin.ai.AiModels.AiChatInput;
import com.hchat.server.plugin.ai.AiModels.AiChatResult;
import com.hchat.server.plugin.ai.AiModels.AiMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 会话：SSE 流式输出，用户消息与 AI 回复都会落库并广播。
 *
 * <p>事件：{@code ai.delta} / {@code ai.done} / {@code error}。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final int CONTEXT_MESSAGES = 20;

    private final AiAgentMapper agentMapper;
    private final AiRunMapper runMapper;
    private final MessageMapper messageMapper;
    private final MessageService messageService;
    private final AIProviderRegistry registry;
    private final JsonCodec json;
    private final IdGenerator ids;
    private final HChatProperties props;
    private final NotificationService notifications;
    private final Executor executor;

    public AiService(AiAgentMapper agentMapper, AiRunMapper runMapper, MessageMapper messageMapper,
                     MessageService messageService, AIProviderRegistry registry, JsonCodec json,
                     IdGenerator ids, HChatProperties props, NotificationService notifications,
                     @Qualifier("taskExecutor") Executor executor) {
        this.agentMapper = agentMapper;
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
        this.messageService = messageService;
        this.registry = registry;
        this.json = json;
        this.ids = ids;
        this.props = props;
        this.notifications = notifications;
        this.executor = executor;
    }

    public List<AgentView> listAgents(ChatPrincipal principal) {
        return agentMapper.selectList(new LambdaQueryWrapper<AiAgent>()
                        .eq(AiAgent::getTenantId, principal.tenantId())
                        .eq(AiAgent::getAppId, principal.appId())
                        .orderByAsc(AiAgent::getCreatedAt))
                .stream().map(this::toView).toList();
    }

    public AgentView createAgent(ChatPrincipal principal, CreateAgent request) {
        AiAgent agent = new AiAgent();
        agent.setId(ids.nextId());
        agent.setTenantId(principal.tenantId());
        agent.setAppId(principal.appId());
        agent.setName(request.name());
        agent.setProvider(request.provider() == null || request.provider().isBlank()
                ? props.getAi().getProvider() : request.provider());
        agent.setModel(request.model() == null || request.model().isBlank()
                ? props.getAi().getModel() : request.model());
        agent.setSystemPrompt(request.systemPrompt());
        agent.setConfigJson(json.write(request.config() == null ? Map.of() : request.config()));
        agent.setStatus("active");
        agent.setCreatedAt(Times.now());
        agent.setUpdatedAt(Times.now());
        agentMapper.insert(agent);
        return toView(agent);
    }

    /** 异步执行一次流式问答；事件通过 SSE 推给调用方。 */
    public void stream(ChatPrincipal principal, long conversationId, AiStreamRequest request, SseEmitter emitter) {
        executor.execute(() -> runStream(principal, conversationId, request, emitter));
    }

    private void runStream(ChatPrincipal principal, long conversationId, AiStreamRequest request,
                           SseEmitter emitter) {
        long runId = ids.nextId();
        try {
            Conversation conversation = messageService.requireConversation(principal, conversationId);
            messageService.requireMember(conversation, principal.userId());
            AiAgent agent = resolveAgent(principal, conversation, request.agentId());
            AIProvider provider = registry.resolve(agent.getProvider());

            if (!Boolean.FALSE.equals(request.persistUserMessage())) {
                messageService.send(principal, conversationId,
                        new MessageDtos.Send(request.clientMsgId(), "text", Map.of("text", request.text())));
            }

            AiRun run = new AiRun();
            run.setId(runId);
            run.setTenantId(conversation.getTenantId());
            run.setAppId(conversation.getAppId());
            run.setConversationId(conversationId);
            run.setAgentId(agent.getId());
            run.setStatus("running");
            run.setCreatedAt(Times.now());
            runMapper.insert(run);

            AiChatInput input = new AiChatInput(
                    agent.getModel(),
                    agent.getSystemPrompt(),
                    buildContext(conversationId),
                    props.getAi().getTemperature(),
                    props.getAi().getMaxTokens());

            AiChatResult result = provider.stream(input, chunk ->
                    sendEvent(emitter, "ai.delta", Map.of("runId", String.valueOf(runId), "delta", chunk)));

            MessageDtos.View aiMessage = messageService.appendAiMessage(conversation, agent.getId(), "text",
                    Map.of("text", result.content(), "runId", String.valueOf(runId), "agentId",
                            agent.getId() == null ? "" : String.valueOf(agent.getId())));

            run.setStatus("succeeded");
            run.setMessageId(Long.valueOf(aiMessage.id()));
            run.setUsageJson(json.write(result.usage()));
            run.setFinishedAt(Times.now());
            runMapper.updateById(run);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("runId", String.valueOf(runId));
            done.put("message", aiMessage);
            done.put("usage", result.usage());
            sendEvent(emitter, "ai.done", done);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", String.valueOf(conversationId));
            payload.put("runId", String.valueOf(runId));
            payload.put("message", aiMessage);
            notifications.publish(conversation.getTenantId(), conversation.getAppId(), "ai.completed", payload);
        } catch (ApiException e) {
            failRun(runId, e.getMessage());
            sendEvent(emitter, "error", Map.of("code", e.errorCode().code(), "message",
                    e.getMessage() == null ? e.errorCode().message() : e.getMessage()));
        } catch (Exception e) {
            log.error("AI 流式回答失败", e);
            failRun(runId, e.getMessage());
            sendEvent(emitter, "error", Map.of("code", ErrorCode.AI_PROVIDER_ERROR.code(),
                    "message", "AI 服务异常: " + e.getMessage()));
        } finally {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 客户端已断开
            }
        }
    }

    private void failRun(long runId, String error) {
        try {
            AiRun run = runMapper.selectById(runId);
            if (run != null) {
                run.setStatus("failed");
                run.setError(error == null ? "unknown" : (error.length() > 1000 ? error.substring(0, 1000) : error));
                run.setFinishedAt(Times.now());
                runMapper.updateById(run);
            }
        } catch (Exception e) {
            log.debug("更新 AI run 失败: {}", e.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "SSE 推送失败: " + e.getMessage());
        }
    }

    private List<AiMessage> buildContext(long conversationId) {
        List<Message> history = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + CONTEXT_MESSAGES));
        List<AiMessage> result = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if ("recalled".equals(message.getStatus())) {
                continue;
            }
            String role = switch (message.getSenderType()) {
                case "ai" -> "assistant";
                case "system" -> "system";
                default -> "user";
            };
            String text = extractText(message);
            if (!text.isBlank()) {
                result.add(new AiMessage(role, text));
            }
        }
        return result;
    }

    private String extractText(Message message) {
        Map<String, Object> content = json.readMap(message.getContentJson());
        Object text = content.get("text");
        if (text != null) {
            return String.valueOf(text);
        }
        return ViewAssembler.preview(message.getType(), content);
    }

    private AiAgent resolveAgent(ChatPrincipal principal, Conversation conversation, Long requestedAgentId) {
        Long agentId = requestedAgentId;
        if (agentId == null) {
            Object fromMetadata = json.readMap(conversation.getMetadataJson()).get("agentId");
            if (fromMetadata instanceof String s && s.matches("\\d+")) {
                agentId = Long.valueOf(s);
            }
        }
        AiAgent agent = null;
        if (agentId != null) {
            agent = agentMapper.selectById(agentId);
            if (agent == null || !agent.getAppId().equals(principal.appId())) {
                throw new ApiException(ErrorCode.AI_AGENT_NOT_FOUND);
            }
        }
        if (agent == null) {
            agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                    .eq(AiAgent::getAppId, principal.appId())
                    .eq(AiAgent::getStatus, "active")
                    .orderByAsc(AiAgent::getCreatedAt)
                    .last("LIMIT 1"));
        }
        if (agent == null) {
            // 没有任何 agent 配置时使用内置默认（不落库），保证开箱可用
            agent = new AiAgent();
            agent.setName("default");
            agent.setProvider(props.getAi().getProvider());
            agent.setModel(props.getAi().getModel());
            agent.setSystemPrompt("你是 HChat 的内置助手，回答简洁、准确、有帮助。");
        }
        return agent;
    }

    private AgentView toView(AiAgent agent) {
        return new AgentView(String.valueOf(agent.getId()), agent.getName(), agent.getProvider(),
                agent.getModel(), agent.getSystemPrompt(), agent.getStatus());
    }
}
