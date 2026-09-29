package com.atguigu.java.ai.langchain4j;

import com.atguigu.java.ai.langchain4j.config.CachedContentRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自检：同一问题第二次提问必须命中 Redis 缓存，不再走下游向量检索。
 * 依赖本机 Redis（localhost:6379），跑法：mvn test -Dtest=CachedContentRetrieverTest
 */
class CachedContentRetrieverTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    @Test
    void secondCallHitsCacheAndSkipsDelegate() {
        AtomicInteger delegateCalls = new AtomicInteger();
        ContentRetriever delegate = query -> {
            delegateCalls.incrementAndGet();
            return List.of(Content.from("肺癌早期症状包括咳嗽、咯血、胸痛。"));
        };

        CachedContentRetriever retriever =
                new CachedContentRetriever(delegate, redis, Duration.ofMinutes(10));

        List<Content> first = retriever.retrieve(Query.from("肺癌的早期症状有哪些"));
        // 第二次故意多带前后空格，验证 normalize 后命中同一条缓存
        List<Content> second = retriever.retrieve(Query.from("  肺癌的早期症状有哪些  "));

        assertEquals(1, delegateCalls.get(), "第二次提问不应该再走下游检索");
        assertEquals("肺癌早期症状包括咳嗽、咯血、胸痛。", second.get(0).textSegment().text());
        assertEquals(first.get(0).textSegment().text(), second.get(0).textSegment().text());
    }
}
