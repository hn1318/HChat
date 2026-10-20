package com.hchat.server.api;

import com.hchat.server.api.dto.ConversationDtos;
import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.auth.CurrentUser;
import com.hchat.server.service.ConversationService;
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

@Tag(name = "Conversations", description = "会话管理")
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @Operation(summary = "创建会话（支持 dedupeKey 去重；c2ai 自动挂载默认智能体）")
    @PostMapping
    public ConversationDtos.View create(@Valid @RequestBody ConversationDtos.Create request) {
        return conversations.create(CurrentUser.require(), request);
    }

    @Operation(summary = "当前用户的会话列表（游标分页，含未读数与最后一条消息摘要）")
    @GetMapping
    public MessageDtos.Page<ConversationDtos.View> list(@RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) Integer limit) {
        return conversations.list(CurrentUser.require(), cursor, limit);
    }

    @Operation(summary = "会话详情（含成员与已读位置）")
    @GetMapping("/{id}")
    public ConversationDtos.View get(@PathVariable("id") long id) {
        return conversations.get(CurrentUser.require(), id);
    }

    @Operation(summary = "新增成员（按业务方 externalUserId）")
    @PostMapping("/{id}/members")
    public ConversationDtos.View addMember(@PathVariable("id") long id,
                                           @Valid @RequestBody ConversationDtos.AddMember request) {
        return conversations.addMember(CurrentUser.require(), id, request);
    }
}
