package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.Conversation;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Conversation 数据访问。会话列表按成员关系过滤，游标分页走 (last_message_at, id) 组合。 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Select("""
            <script>
            SELECT c.* FROM conversations c
            JOIN conversation_members m ON m.conversation_id = c.id
            WHERE m.tenant_id = #{tenantId}
              AND m.app_id = #{appId}
              AND m.user_id = #{userId}
            <if test="cursorTime != null">
              AND (COALESCE(c.last_message_at, c.created_at) &lt; #{cursorTime}
                   OR (COALESCE(c.last_message_at, c.created_at) = #{cursorTime} AND c.id &lt; #{cursorId}))
            </if>
            ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Conversation> listForUser(@Param("tenantId") String tenantId,
                                   @Param("appId") String appId,
                                   @Param("userId") Long userId,
                                   @Param("cursorTime") Instant cursorTime,
                                   @Param("cursorId") Long cursorId,
                                   @Param("limit") int limit);
}
