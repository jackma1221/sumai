package com.hmall.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.item.domain.po.SearchHotWord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 搜索热词 Mapper：命中一条就累加一次，没有则插入。
 */
public interface SearchHotWordMapper extends BaseMapper<SearchHotWord> {

    @Insert("INSERT INTO search_hot_word(keyword, search_count, update_time) VALUES(#{keyword}, 1, NOW()) " +
            "ON DUPLICATE KEY UPDATE search_count = search_count + 1, update_time = NOW()")
    int increase(@Param("keyword") String keyword);
}
