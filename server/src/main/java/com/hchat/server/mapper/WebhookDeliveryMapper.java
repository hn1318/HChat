package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.WebhookDelivery;
import org.apache.ibatis.annotations.Mapper;

/** WebhookDelivery 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {}
