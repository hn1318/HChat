package com.hchat.server.api;

import com.hchat.server.api.dto.ResourceDtos.AgentView;
import com.hchat.server.api.dto.ResourceDtos.AiStreamRequest;
import com.hchat.server.api.dto.ResourceDtos.CreateAgent;
import com.hchat.server.api.dto.ResourceDtos.UploadResult;
import com.hchat.server.auth.CurrentUser;
import com.hchat.server.service.AiService;
import com.hchat.server.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Files & AI", description = "文件上传、AI 智能体与流式对话")
@RestController
@RequestMapping("/api/v1")
public class ResourceController {

    private final FileService files;
    private final AiService ai;

    public ResourceController(FileService files, AiService ai) {
        this.files = files;
        this.ai = ai;
    }

    @Operation(summary = "上传文件（本地磁盘或 S3，返回可访问 URL）")
    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@RequestPart("file") MultipartFile file) {
        return files.upload(CurrentUser.require(), file);
    }

    @Operation(summary = "当前 app 的智能体列表")
    @GetMapping("/ai/agents")
    public List<AgentView> listAgents() {
        return ai.listAgents(CurrentUser.require());
    }

    @Operation(summary = "创建智能体")
    @PostMapping("/ai/agents")
    public AgentView createAgent(@Valid @RequestBody CreateAgent request) {
        return ai.createAgent(CurrentUser.require(), request);
    }

    /**
     * SSE 流式回答。事件：{@code ai.delta}（增量文本）、{@code ai.done}（落库后的消息）、{@code error}。
     */
    @Operation(summary = "AI 流式对话（SSE）")
    @PostMapping(value = "/conversations/{id}/ai/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") long conversationId,
                             @Valid @RequestBody AiStreamRequest request,
                             @RequestParam(value = "timeout", required = false) Long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs == null ? 180_000L : timeoutMs);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> emitter.complete());
        ai.stream(CurrentUser.require(), conversationId, request, emitter);
        return emitter;
    }
}
