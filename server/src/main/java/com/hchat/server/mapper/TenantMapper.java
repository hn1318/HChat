package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;

/** Tenant 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {}
