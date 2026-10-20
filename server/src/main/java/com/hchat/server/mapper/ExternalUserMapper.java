package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.ExternalUser;
import org.apache.ibatis.annotations.Mapper;

/** ExternalUser 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface ExternalUserMapper extends BaseMapper<ExternalUser> {}
