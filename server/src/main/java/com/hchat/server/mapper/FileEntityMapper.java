package com.hchat.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hchat.server.domain.FileEntity;
import org.apache.ibatis.annotations.Mapper;

/** FileEntity 数据访问（MyBatis-Plus BaseMapper，复杂 SQL 见 Mapper XML）。 */
@Mapper
public interface FileEntityMapper extends BaseMapper<FileEntity> {}
