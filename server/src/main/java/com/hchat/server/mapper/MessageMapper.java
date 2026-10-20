package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.Message;
import org.apache.ibatis.annotations.Mapper;

/** Message 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {}
