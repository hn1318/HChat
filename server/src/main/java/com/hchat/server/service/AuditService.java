package com.hchat.server.service;

import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.domain.AuditLog;
import com.hchat.server.mapper.AuditLogMapper;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 审计日志：异步落库，失败不影响主流程。 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogMapper mapper;
    private final IdGenerator ids;
    private final JsonCodec json;

    public AuditService(AuditLogMapper mapper, IdGenerator ids, JsonCodec json) {
        this.mapper = mapper;
        this.ids = ids;
        this.json = json;
    }

    @Async
    public void log(String tenantId, String appId, Long actorId, String action,
                    String targetType, String targetId, Map<String, Object> metadata) {
        try {
            AuditLog entry = new AuditLog();
            entry.setId(ids.nextId());
            entry.setTenantId(tenantId);
            entry.setAppId(appId);
            entry.setActorId(actorId);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setMetadataJson(json.write(metadata == null ? Map.of() : metadata));
            entry.setCreatedAt(Times.now());
            mapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计日志写入失败 action={}: {}", action, e.getMessage());
        }
    }
}
