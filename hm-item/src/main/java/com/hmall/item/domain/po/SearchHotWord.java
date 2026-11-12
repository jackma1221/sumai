package com.hmall.item.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 搜索热词统计（第 12 张表）：记录关键词搜索次数，用于搜索框联想与热门推荐。
 */
@Data
@Accessors(chain = true)
@TableName("search_hot_word")
public class SearchHotWord {

    private Long id;
    private String keyword;
    private Integer searchCount;
    private LocalDateTime updateTime;
}
