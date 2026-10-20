package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.WebhookEndpoint;
import org.apache.ibatis.annotations.Mapper;

/** WebhookEndpoint 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface WebhookEndpointMapper extends BaseMapper<WebhookEndpoint> {}
