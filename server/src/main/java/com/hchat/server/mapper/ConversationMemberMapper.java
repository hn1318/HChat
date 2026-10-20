package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.ConversationMember;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** ConversationMember 数据访问：成员关系与未读计数。 */
@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    @Select("""
            SELECT * FROM conversation_members
            WHERE conversation_id = #{conversationId} AND tenant_id = #{tenantId} AND app_id = #{appId}
            ORDER BY joined_at ASC
            """)
    List<ConversationMember> listByConversation(@Param("tenantId") String tenantId,
                                                @Param("appId") String appId,
                                                @Param("conversationId") Long conversationId);

    /** 除发送者外，所有成员的未读数 +1。 */
    @Update("""
            UPDATE conversation_members SET unread_count = unread_count + 1
            WHERE conversation_id = #{conversationId} AND tenant_id = #{tenantId} AND app_id = #{appId}
              AND user_id <> #{senderId}
            """)
    int incrementUnread(@Param("tenantId") String tenantId,
                        @Param("appId") String appId,
                        @Param("conversationId") Long conversationId,
                        @Param("senderId") Long senderId);

    /** 清空未读并推进已读位置（只前进不后退）。 */    @Update("""
            UPDATE conversation_members SET unread_count = 0, last_read_message_id = #{messageId}
            WHERE conversation_id = #{conversationId} AND tenant_id = #{tenantId} AND app_id = #{appId}
              AND user_id = #{userId}
              AND (last_read_message_id IS NULL OR last_read_message_id < #{messageId})
            """)
    int markRead(@Param("tenantId") String tenantId,
                 @Param("appId") String appId,
                 @Param("conversationId") Long conversationId,
                 @Param("userId") Long userId,
                 @Param("messageId") Long messageId);

    /** 该用户所有会话中的其他成员（用于在线状态广播）。 */
    @Select("""
            SELECT DISTINCT user_id FROM conversation_members
            WHERE app_id = #{appId}
              AND user_id <> #{userId}
              AND conversation_id IN (
                  SELECT conversation_id FROM conversation_members
                  WHERE app_id = #{appId} AND user_id = #{userId}
              )
            """)
    List<Long> findPeers(@Param("appId") String appId, @Param("userId") Long userId);
}
