package com.hchat.server.api;

import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.auth.CurrentUser;
import com.hchat.server.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Messages", description = "消息收发 / 已读 / 撤回")
@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @Operation(summary = "拉取历史消息：不传参数=最新一页，before=向前翻页，after=断线补拉")
    @GetMapping("/conversations/{id}/messages")
    public MessageDtos.Page<MessageDtos.View> list(@PathVariable("id") long conversationId,
                                                   @RequestParam(required = false) Long before,
                                                   @RequestParam(required = false) Long after,
                                                   @RequestParam(required = false) Integer limit) {
        return messages.list(CurrentUser.require(), conversationId, before, after, limit);
    }

    @Operation(summary = "发送消息（clientMsgId 幂等）")
    @PostMapping("/conversations/{id}/messages")
    public MessageDtos.View send(@PathVariable("id") long conversationId,
                                 @Valid @RequestBody MessageDtos.Send request) {
        return messages.send(CurrentUser.require(), conversationId, request);
    }

    @Operation(summary = "标记已读（不传 messageId 则以会话最后一条为准）")
    @PostMapping("/conversations/{id}/read")
    public void markRead(@PathVariable("id") long conversationId,
                         @RequestBody(required = false) MessageDtos.ReadRequest request) {
        messages.markRead(CurrentUser.require(), conversationId, request == null ? null : request.messageId());
    }

    @Operation(summary = "撤回消息（仅本人，默认 2 分钟内）")
    @PostMapping("/messages/{id}/recall")
    public MessageDtos.View recall(@PathVariable("id") long messageId) {
        return messages.recall(CurrentUser.require(), messageId);
    }
}
