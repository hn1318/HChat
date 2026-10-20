package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/** AuditLog 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {}
