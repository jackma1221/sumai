package com.hmall.search;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.domain.po.Item;
import com.hmall.domain.query.ItemPageQuery;
import com.hmall.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品 ES 索引与检索服务。
 * 直接用 RestHighLevelClient + SearchSourceBuilder，避免 Spring Data 版本差异带来的 API 不兼容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemIndexService {

    /** 索引名 */
    public static final String INDEX = "item";
    /** 全量同步每批条数 */
    private static final int BATCH_SIZE = 1000;

    /** 索引结构：品牌/类目用 keyword 做精确过滤与聚合，名称用 text 做全文检索 */
    private static final String MAPPING = "{\n" +
            "  \"properties\": {\n" +
            "    \"id\": {\"type\": \"long\"},\n" +
            "    \"name\": {\"type\": \"text\", \"fields\": {\"keyword\": {\"type\": \"keyword\", \"ignore_above\": 256}}},\n" +
            "    \"price\": {\"type\": \"integer\"},\n" +
            "    \"stock\": {\"type\": \"integer\"},\n" +
            "    \"image\": {\"type\": \"keyword\", \"index\": false},\n" +
            "    \"category\": {\"type\": \"keyword\"},\n" +
            "    \"brand\": {\"type\": \"keyword\"},\n" +
            "    \"spec\": {\"type\": \"text\"},\n" +
            "    \"sold\": {\"type\": \"integer\"},\n" +
            "    \"commentCount\": {\"type\": \"integer\"},\n" +
            "    \"isAD\": {\"type\": \"boolean\"},\n" +
            "    \"status\": {\"type\": \"integer\"}\n" +
            "  }\n" +
            "}";

    private final RestHighLevelClient restHighLevelClient;
    private final ObjectMapper objectMapper;
    private final ItemMapper itemMapper;

    /** 判断索引是否存在 */
    public boolean indexExists() throws IOException {
        return restHighLevelClient.indices().exists(new GetIndexRequest(INDEX), RequestOptions.DEFAULT);
    }

    /** 索引不存在则创建（单机环境副本数 0，避免 yellow 状态） */
    public void ensureIndex() throws IOException {
        if (indexExists()) {
            return;
        }
        CreateIndexRequest request = new CreateIndexRequest(INDEX);
        request.settings("{\"number_of_shards\":1,\"number_of_replicas\":0}", XContentType.JSON);
        request.mapping(MAPPING, XContentType.JSON);
        restHighLevelClient.indices().create(request, RequestOptions.DEFAULT);
        log.info("ES 索引 {} 创建完成", INDEX);
    }

    /** 删除索引（重建时使用） */
    public void dropIndex() throws IOException {
        if (indexExists()) {
            restHighLevelClient.indices().delete(new DeleteIndexRequest(INDEX), RequestOptions.DEFAULT);
        }
    }

    /** 索引内文档数 */
    public long count() throws IOException {
        return restHighLevelClient.count(new CountRequest(INDEX), RequestOptions.DEFAULT).getCount();
    }

    /**
     * 全量重建：按 id 游标分页读 MySQL，每 1000 条批量写入 ES，避免一次把 8 万多条商品全读进内存
     */
    public int rebuildAll() throws IOException {
        ensureIndex();
        long lastId = 0L;
        int total = 0;
        while (true) {
            List<Item> batch = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                    .gt(Item::getId, lastId)
                    .orderByAsc(Item::getId)
                    .last("limit " + BATCH_SIZE));
            if (CollUtils.isEmpty(batch)) {
                break;
            }
            bulkIndex(batch);
            lastId = batch.get(batch.size() - 1).getId();
            total += batch.size();
            log.info("ES 商品索引已同步 {} 条", total);
        }
        return total;
    }

    private void bulkIndex(List<Item> items) throws IOException {
        BulkRequest bulk = new BulkRequest();
        for (Item item : items) {
            ItemDoc doc = BeanUtils.copyBean(item, ItemDoc.class);
            bulk.add(new IndexRequest(INDEX).id(String.valueOf(item.getId()))
                    .source(objectMapper.writeValueAsBytes(doc), XContentType.JSON));
        }
        BulkResponse response = restHighLevelClient.bulk(bulk, RequestOptions.DEFAULT);
        if (response.hasFailures()) {
            log.warn("ES 批量写入存在失败：{}", response.buildFailureMessage());
        }
    }

    /** 单条写入/更新（商品新增、修改时调用，异常不影响主流程） */
    public void upsert(Item item) {
        try {
            ensureIndex();
            ItemDoc doc = BeanUtils.copyBean(item, ItemDoc.class);
            restHighLevelClient.index(new IndexRequest(INDEX).id(String.valueOf(item.getId()))
                    .source(objectMapper.writeValueAsBytes(doc), XContentType.JSON), RequestOptions.DEFAULT);
        } catch (Exception e) {
            log.warn("ES 写入商品失败，id={}，原因={}", item.getId(), e.getMessage());
        }
    }

    /** 按 id 重新同步单条文档（销量/库存变化后调用） */
    public void syncById(Long id) {
        try {
            Item item = itemMapper.selectById(id);
            if (item == null) {
                delete(id);
                return;
            }
            upsert(item);
        } catch (Exception e) {
            log.warn("ES 同步商品失败，id={}，原因={}", id, e.getMessage());
        }
    }

    /** 删除文档 */
    public void delete(Long id) {
        try {
            if (indexExists()) {
                restHighLevelClient.delete(new DeleteRequest(INDEX, String.valueOf(id)), RequestOptions.DEFAULT);
            }
        } catch (Exception e) {
            log.warn("ES 删除商品失败，id={}，原因={}", id, e.getMessage());
        }
    }

    /**
     * 检索：关键字全文匹配 + 品牌/类目/价格过滤 + 价格或销量排序 + 名称高亮 + 品牌/类目聚合
     */
    public ItemSearchResult search(ItemPageQuery query) throws IOException {
        int pageNo = query.getPageNo() == null || query.getPageNo() < 1 ? 1 : query.getPageNo();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : Math.min(query.getPageSize(), 100);

        // 1. 主查询：状态过滤 + 关键字 + 多维过滤
        BoolQueryBuilder bool = buildQuery(query, true);
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(bool)
                .from((pageNo - 1) * pageSize)
                .size(pageSize)
                .trackTotalHits(true);
        // 2. 排序：price/sold 支持升降序，其余按相关度打分
        if (StrUtil.isNotBlank(query.getSortBy())) {
            SortOrder order = Boolean.FALSE.equals(query.getIsAsc()) ? SortOrder.DESC : SortOrder.ASC;
            if ("price".equals(query.getSortBy())) {
                source.sort(SortBuilders.fieldSort("price").order(order));
            } else if ("sold".equals(query.getSortBy())) {
                source.sort(SortBuilders.fieldSort("sold").order(order));
            }
        }
        // 3. 名称高亮
        source.highlighter(new HighlightBuilder()
                .field("name")
                .preTags("<span class=\"hl\">")
                .postTags("</span>")
                .fragmentSize(120)
                .numOfFragments(1));

        SearchRequest request = new SearchRequest(INDEX).source(source);
        SearchResponse response = restHighLevelClient.search(request, RequestOptions.DEFAULT);

        List<ItemSearchResult.ItemHit> hits = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            ItemDoc doc = objectMapper.readValue(hit.getSourceAsString(), ItemDoc.class);
            String highlight = hit.getHighlightFields().containsKey("name")
                    ? hit.getHighlightFields().get("name").getFragments()[0].string() : null;
            hits.add(new ItemSearchResult.ItemHit(doc, highlight));
        }

        ItemSearchResult result = new ItemSearchResult();
        long total = response.getHits().getTotalHits() == null ? 0 : response.getHits().getTotalHits().value;
        result.setTotal(total);
        result.setPageNo(pageNo);
        result.setPageSize(pageSize);
        result.setTotalPages((total + pageSize - 1) / pageSize);
        result.setItems(hits);
        // 4. 聚合单独查一次（只保留关键字与状态条件），这样切品牌时其它品牌仍可作为筛选项
        result.setBrands(facets(query, "brand"));
        result.setCategories(facets(query, "category"));
        return result;
    }

    private List<ItemSearchResult.Facet> facets(ItemPageQuery query, String field) throws IOException {
        BoolQueryBuilder bool = buildQuery(query, false);
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(bool)
                .size(0)
                .aggregation(AggregationBuilders.terms(field + "Agg").field(field).size(30));
        SearchResponse response = restHighLevelClient.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
        Terms terms = response.getAggregations().get(field + "Agg");
        List<ItemSearchResult.Facet> facets = new ArrayList<>();
        if (terms != null) {
            for (Terms.Bucket bucket : terms.getBuckets()) {
                facets.add(new ItemSearchResult.Facet(bucket.getKeyAsString(), bucket.getDocCount()));
            }
        }
        return facets;
    }

    /**
     * 门户搜索页的筛选栏数据：{品牌: [...], 分类: [...]}。
     * 只带关键字与状态条件，不带已选过滤项，保证切换某个品牌时其它品牌仍可选。
     */
    public Map<String, List<String>> filterOptions(ItemPageQuery query) throws IOException {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("brand", facetValues(query, "brand"));
        options.put("category", facetValues(query, "category"));
        return options;
    }

    private List<String> facetValues(ItemPageQuery query, String field) throws IOException {
        List<ItemSearchResult.Facet> facets = facets(query, field);
        List<String> values = new ArrayList<>(facets.size());
        for (ItemSearchResult.Facet facet : facets) {
            values.add(facet.getName());
        }
        return values;
    }

    /**
     * 搜索框联想词：用关键字命中的商品做一次品牌聚合，返回出现最多的品牌名。
     */
    public List<String> suggest(ItemPageQuery query) throws IOException {
        if (StrUtil.isBlank(query.getKey())) {
            return Collections.emptyList();
        }
        BoolQueryBuilder bool = buildQuery(query, false);
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(bool)
                .size(0)
                .aggregation(AggregationBuilders.terms("suggestAgg").field("brand").size(10));
        SearchResponse response = restHighLevelClient.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
        Terms terms = response.getAggregations().get("suggestAgg");
        List<String> values = new ArrayList<>();
        if (terms != null) {
            for (Terms.Bucket bucket : terms.getBuckets()) {
                values.add(bucket.getKeyAsString());
            }
        }
        return values;
    }

    /**
     * 组装查询条件
     *
     * @param withFilters true 时带上品牌/类目/价格过滤（主查询）；false 时只保留关键字与状态（聚合查询）
     */
    private BoolQueryBuilder buildQuery(ItemPageQuery query, boolean withFilters) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        bool.filter(QueryBuilders.termQuery("status", 1));
        if (StrUtil.isNotBlank(query.getKey())) {
            // 名称权重最高，其次品牌、类目、规格；用 bool should 组合，避免 multi_match 字段 boost 语法在不同 ES 版本上的兼容问题
            bool.must(QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("name", query.getKey().trim()).boost(3f))
                    .should(QueryBuilders.matchQuery("brand", query.getKey().trim()).boost(2f))
                    .should(QueryBuilders.matchQuery("category", query.getKey().trim()))
                    .should(QueryBuilders.matchQuery("spec", query.getKey().trim()))
                    .minimumShouldMatch(1));
        }
        if (!withFilters) {
            return bool;
        }
        if (StrUtil.isNotBlank(query.getBrand())) {
            bool.filter(QueryBuilders.termQuery("brand", query.getBrand()));
        }
        if (StrUtil.isNotBlank(query.getCategory())) {
            bool.filter(QueryBuilders.termQuery("category", query.getCategory()));
        }
        if (query.getMinPrice() != null || query.getMaxPrice() != null) {
            bool.filter(QueryBuilders.rangeQuery("price")
                    .gte(query.getMinPrice() == null ? 0 : query.getMinPrice())
                    .lte(query.getMaxPrice() == null ? Integer.MAX_VALUE : query.getMaxPrice()));
        }
        return bool;
    }
}
