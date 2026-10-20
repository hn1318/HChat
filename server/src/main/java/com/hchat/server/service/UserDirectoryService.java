package com.hchat.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.domain.ExternalUser;
import com.hchat.server.mapper.ExternalUserMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部用户目录：外部用户 ID → 内部用户。
 *
 * <p>业务方可以先建会话后登录，所以这里按需创建「影子用户」。
 */
@Service
public class UserDirectoryService {

    private final ExternalUserMapper mapper;
    private final IdGenerator ids;
    private final JsonCodec json;

    public UserDirectoryService(ExternalUserMapper mapper, IdGenerator ids, JsonCodec json) {
        this.mapper = mapper;
        this.ids = ids;
        this.json = json;
    }

    @Transactional
    public ExternalUser resolve(String tenantId, String appId, String externalUserId,
                               String displayName, String avatarUrl, Map<String, Object> metadata) {
        ExternalUser existing = find(appId, externalUserId);
        Instant now = Times.now();
        if (existing == null) {
            ExternalUser user = new ExternalUser();
            user.setId(ids.nextId());
            user.setTenantId(tenantId);
            user.setAppId(appId);
            user.setExternalUserId(externalUserId);
            user.setDisplayName(displayName);
            user.setAvatarUrl(avatarUrl);
            user.setMetadataJson(json.write(metadata == null ? Map.of() : metadata));
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            try {
                mapper.insert(user);
                return user;
            } catch (DuplicateKeyException race) {
                ExternalUser concurrent = find(appId, externalUserId);
                if (concurrent == null) {
                    throw race;
                }
                return concurrent;
            }
        }
        boolean dirty = false;
        if (displayName != null && !displayName.equals(existing.getDisplayName())) {
            existing.setDisplayName(displayName);
            dirty = true;
        }
        if (avatarUrl != null && !avatarUrl.equals(existing.getAvatarUrl())) {
            existing.setAvatarUrl(avatarUrl);
            dirty = true;
        }
        if (metadata != null && !metadata.isEmpty()) {
            existing.setMetadataJson(json.write(metadata));
            dirty = true;
        }
        if (dirty) {
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
        }
        return existing;
    }

    public ExternalUser find(String appId, String externalUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<ExternalUser>()
                .eq(ExternalUser::getAppId, appId)
                .eq(ExternalUser::getExternalUserId, externalUserId)
                .last("LIMIT 1"));
    }

    public ExternalUser get(Long userId) {
        return userId == null ? null : mapper.selectById(userId);
    }

    public Map<Long, ExternalUser> mapByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<ExternalUser> users = mapper.selectBatchIds(userIds.stream().distinct().toList());
        return users.stream().collect(Collectors.toMap(ExternalUser::getId, Function.identity(), (a, b) -> a));
    }
}
