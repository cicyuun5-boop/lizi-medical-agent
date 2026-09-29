package com.atguigu.java.ai.langchain4j.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 给任意 ContentRetriever 包一层 Redis 缓存。
 *
 * 同一个问题第二次提问时直接命中缓存，不再调用 EmbeddingModel（走百炼）和 Pinecone。
 */
public class CachedContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(CachedContentRetriever.class);
    private static final String KEY_PREFIX = "xiaozhi:rag:retrieve:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContentRetriever delegate;
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CachedContentRetriever(ContentRetriever delegate, StringRedisTemplate redis, Duration ttl) {
        this.delegate = delegate;
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String question = normalize(query.text());
        String key = KEY_PREFIX + DigestUtils.md5DigestAsHex(question.getBytes(StandardCharsets.UTF_8));

        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                List<Content> contents = deserialize(cached);
                hits.increment();
                log.info("[RAG缓存] 命中 query=[{}] 累计命中/未命中={}/{}",
                        question, hits.sum(), misses.sum());
                return contents;
            }
        } catch (Exception e) {
            // 缓存是加速手段、不是数据源：Redis 挂了要降级直查，不能把整个问答带崩
            log.warn("[RAG缓存] 读取失败，降级为直接检索：{}", e.getMessage());
        }

        List<Content> contents = delegate.retrieve(query);
        misses.increment();
        log.info("[RAG缓存] 未命中 query=[{}] 累计命中/未命中={}/{}",
                question, hits.sum(), misses.sum());

        try {
            redis.opsForValue().set(key, serialize(contents), ttl);
        } catch (Exception e) {
            log.warn("[RAG缓存] 写入失败，不影响本次回答：{}", e.getMessage());
        }
        return contents;
    }

    /** 统一空白字符，避免同一句话因为空格/换行差异产生两条缓存 */
    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** Content 不是标准 JavaBean，Jackson 直接序列化会丢内容，所以手工转成 {text, metadata} */
    private static String serialize(List<Content> contents) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(contents.size());
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", segment.text());
            row.put("metadata", segment.metadata().toMap());
            rows.add(row);
        }
        return MAPPER.writeValueAsString(rows);
    }

    @SuppressWarnings("unchecked")
    private static List<Content> deserialize(String json) throws Exception {
        List<Map<String, Object>> rows =
                MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        List<Content> contents = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> rawMetadata = (Map<String, Object>) row.get("metadata");
            Metadata metadata = rawMetadata == null ? new Metadata() : Metadata.from(rawMetadata);
            contents.add(Content.from(TextSegment.from((String) row.get("text"), metadata)));
        }
        return contents;
    }
}
