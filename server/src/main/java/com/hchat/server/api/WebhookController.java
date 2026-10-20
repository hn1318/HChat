package com.hchat.server.api;

import com.hchat.server.api.dto.ResourceDtos.CreateWebhook;
import com.hchat.server.api.dto.ResourceDtos.WebhookView;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.auth.CurrentUser;
import com.hchat.server.service.AuditService;
import com.hchat.server.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Webhooks", description = "事件订阅（HMAC 签名 + 指数退避重试）")
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookService webhooks;
    private final AuditService audit;

    public WebhookController(WebhookService webhooks, AuditService audit) {
        this.webhooks = webhooks;
        this.audit = audit;
    }

    @Operation(summary = "当前 app 的 Webhook 端点")
    @GetMapping
    public List<WebhookView> list() {
        ChatPrincipal principal = CurrentUser.require();
        return webhooks.list(principal.tenantId(), principal.appId());
    }

    @Operation(summary = "创建 Webhook 端点（不传 secret 则服务端随机生成）")
    @PostMapping
    public WebhookView create(@Valid @RequestBody CreateWebhook request) {
        ChatPrincipal principal = CurrentUser.require();
        WebhookView view = webhooks.create(principal.tenantId(), principal.appId(), request);
        audit.log(principal.tenantId(), principal.appId(), principal.userId(), "webhook.create",
                "webhook", view.id(), Map.of("url", view.url()));
        return view;
    }
}
