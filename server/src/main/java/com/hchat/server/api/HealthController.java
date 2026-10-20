package com.hchat.server.api;

import com.hchat.server.common.Times;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.plugin.ai.AIProviderRegistry;
import com.hchat.server.plugin.storage.StorageProvider;
import com.hchat.server.ws.WsSessionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查：不依赖鉴权，供容器探针与部署脚本使用。 */
@Tag(name = "Health")
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final HChatProperties props;
    private final StorageProvider storage;
    private final AIProviderRegistry aiProviders;
    private final WsSessionRegistry wsSessions;

    public HealthController(JdbcTemplate jdbc, HChatProperties props, StorageProvider storage,
                            AIProviderRegistry aiProviders, WsSessionRegistry wsSessions) {
        this.jdbc = jdbc;
        this.props = props;
        this.storage = storage;
        this.aiProviders = aiProviders;
        this.wsSessions = wsSessions;
    }

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("ok", true);
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            db.put("ok", false);
            db.put("error", e.getClass().getSimpleName());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", Boolean.TRUE.equals(db.get("ok")) ? "ok" : "degraded");
        body.put("version", "0.1.0");
        body.put("time", Times.now().toString());
        body.put("database", db);
        body.put("storage", storage.name());
        body.put("aiProvider", props.getAi().getProvider());
        body.put("aiProviders", aiProviders.available());
        body.put("redisEnabled", props.getRedis().isEnabled());
        body.put("devMode", props.isDevMode());
        body.put("wsSessions", wsSessions.sessionCount());
        return body;
    }
}
