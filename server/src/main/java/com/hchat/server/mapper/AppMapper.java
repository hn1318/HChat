package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.App;
import org.apache.ibatis.annotations.Mapper;

/** App 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface AppMapper extends BaseMapper<App> {}
