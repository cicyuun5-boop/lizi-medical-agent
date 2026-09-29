package com.atguigu.java.ai.langchain4j.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 动态元数据过滤：把用户问句映射成一个 Pinecone 元数据过滤条件，用来在向量检索前**缩小候选集**。
 *
 * 为什么需要它：Gate A 之后单块已经自解释，但「赵继志擅长什么」这类问句的正确答案块在
 * 全库 61 块里只排第 6 名开外（k=1 时 Passage Recall 只有 17/30）。问句里已经点名了实体，
 * 却让向量检索去猜 —— 这是浪费。按 entity/dept 过滤能把候选集从 61 块缩到 1～17 块。
 *
 * 词典从哪来（关键）：**不硬编码关键词表**，而是启动时把 knowledge 目录按同一个
 * {@link SectionAwareSplitter} 走一遍，收集索引里**真实存在的** dept / entity 取值。
 * 只解析、不调 embedding，所以零成本，且与 xiaozhi.knowledge.ingest-on-startup 开关无关
 * （开关为 false 时索引不重灌，但词典照样可用）。
 *
 * 纯函数约束（硬要求）：映射只依赖 query 文本 + 启动时固定的词典，不含会话状态。
 * 因为 CachedContentRetriever 的缓存 key 只有 query 文本，过滤器带状态就会串味。
 */
public final class KnowledgeMetadataFilter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMetadataFilter.class);

    /** 人名（entity）词典：命中即精确到 1 块 */
    private final Map<String, Filter> byEntity;
    /** 科室（dept）词典：命中即缩到该文档 */
    private final Map<String, Filter> byDept;

    private KnowledgeMetadataFilter(Map<String, Filter> byEntity, Map<String, Filter> byDept) {
        this.byEntity = byEntity;
        this.byDept = byDept;
    }

    /** 交给 EmbeddingStoreContentRetriever.dynamicFilter(...) 的纯函数 */
    public static Function<Query, Filter> dynamicFilter(String knowledgeDir) {
        return index(knowledgeDir)::match;
    }

    /** entity 优先（更精确），其次 dept；都没命中 → null = 不过滤（保守，宁可全库搜也不误杀召回） */
    private Filter match(Query query) {
        String text = query.text();
        Filter hit = firstHit(text, byEntity);
        return hit != null ? hit : firstHit(text, byDept);
    }

    private static KnowledgeMetadataFilter index(String knowledgeDir) {
        Map<String, Filter> entities = new LinkedHashMap<>();
        Map<String, Filter> depts = new LinkedHashMap<>();

        List<Document> documents = ClassPathDocumentLoader.loadDocumentsRecursively(
                knowledgeDir, new TextDocumentParser());
        for (Document document : documents) {
            for (TextSegment segment : new SectionAwareSplitter().split(document)) {
                String dept = segment.metadata().getString("dept");
                if (dept != null && !dept.isEmpty()) {
                    depts.putIfAbsent(dept, MetadataFilterBuilder.metadataKey("dept").isEqualTo(dept));
                }
                String entity = segment.metadata().getString("entity");
                if (entity != null && !entity.isEmpty()) {
                    entities.putIfAbsent(entity, MetadataFilterBuilder.metadataKey("entity").isEqualTo(entity));
                }
            }
        }

        KnowledgeMetadataFilter result = new KnowledgeMetadataFilter(longestFirst(entities), longestFirst(depts));
        log.info("元数据过滤词典就绪：实体 {} 个 {}，科室 {} 个 {}",
                entities.size(), entities.keySet(), depts.size(), depts.keySet());
        return result;
    }

    private static Filter firstHit(String text, Map<String, Filter> dict) {
        for (Map.Entry<String, Filter> entry : dict.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 长词优先（「神经内科」要先于「内科」匹配），同时让遍历顺序确定 —— HashMap 的顺序会让结果飘 */
    private static Map<String, Filter> longestFirst(Map<String, Filter> dict) {
        List<Map.Entry<String, Filter>> entries = new ArrayList<>(dict.entrySet());
        entries.sort(Map.Entry.<String, Filter>comparingByKey(
                Comparator.comparingInt(String::length).reversed()));
        Map<String, Filter> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
