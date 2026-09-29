package com.atguigu.java.ai.langchain4j;

import com.atguigu.java.ai.langchain4j.assistant.LiziAgent;
import com.atguigu.java.ai.langchain4j.config.KnowledgeMetadataFilter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3.5 Gate C —— 多轮指代查询改写评测。
 *
 * 要证明的事：末轮问题只含指代/省略（「那神经内科呢？」）时，把对话历史喂给
 * {@link CompressingQueryTransformer} 改写出的自包含 query，比原句直接检索召回得更好。
 *
 * 两个方法各管一段：
 *  - evalMultiturnRewrite()：离线对照，同一批 case 跑「末轮原句直接检索」和「改写后检索」，
 *    打印逐条 HIT/MISS 与总账。改写器只调 LLM、不碰索引，索引沿用 Gate B 已灌好的那份。
 *  - smokeLiveMultiturn()：端到端冒烟，走真正的 LiziAgent（chatMemory + retrievalAugmentor），
 *    同时验证 @AiService 的 retrievalAugmentor 属性接线正确 —— 属性名或 bean 名写错，Spring 上下文起不来。
 *
 * 跑法（务必先停掉 8080 后端，避免 target/classes 写入冲突）：
 *   $env:JAVA_HOME='C:\Program Files\Java\jdk-21'
 *   mvn test -Dtest=RagMultiturnEvalTest
 *
 * 成本：evalMultiturnRewrite 里改写 = 10 次 qwen-max 调用 + 20 次 embedding；
 * 冒烟 = 2 次 qwen-plus 对话。索引不重灌（依赖 application.properties 的 ingest-on-startup=false）。
 */
@SpringBootTest
class RagMultiturnEvalTest {

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    /** 生产用的那只非流式模型（Assistant.java 里 chatModel="qwenChatModel" 同一只） */
    @Autowired
    @Qualifier("qwenChatModel")
    private ChatLanguageModel qwenChatModel;

    @Autowired
    private LiziAgent liziAgent;

    /** 线上那份动态过滤器，与 LiziAgentConfig 同源，保证评测口径与线上一致 */
    private static final Function<Query, Filter> DYNAMIC_FILTER =
            KnowledgeMetadataFilter.dynamicFilter("knowledge");

    /** 线上检索参数，与 LiziAgentConfig 的 EmbeddingStoreContentRetriever 对齐（maxResults=3、minScore=0） */
    private static final int K = 3;

    /** 一轮多轮样本：历史消息 / 末轮问题 / 期望文档 / 标准答案的原文连续片段 */
    private record Case(List<ChatMessage> history, String lastQuestion, String doc, String snippet) {}

    @Test
    void evalMultiturnRewrite() throws Exception {
        List<Case> cases = loadEvalSet();
        assertEquals(10, cases.size(), "多轮评测集应为 10 条，检查 classpath:eval/rag-multiturn-eval-set.tsv");

        CompressingQueryTransformer transformer = new CompressingQueryTransformer(qwenChatModel);

        int baseHit = 0, rewriteHit = 0, changed = 0;
        System.out.println();
        System.out.printf("%-20s %-34s %-6s %-6s %s%n", "末轮原句", "改写后 query", "基线", "改写", "期望文档");
        System.out.println("-".repeat(94));

        for (Case c : cases) {
            // 基线：末轮原句直接检索。句子含指代，历史被完全忽略 —— 这是 Gate C 要打败的对象。
            boolean base = hitPassage(c.snippet(), search(embed(embeddingModel, c.lastQuestion()), c.lastQuestion()));

            // 改写：历史塞进 Metadata.chatMemory()，CompressingQueryTransformer 从这里读对话。
            // 历史为空时它直接原样返回（零 LLM 成本），所以这里必须有历史才会真调 qwen-max。
            Metadata metadata = Metadata.from(UserMessage.from(c.lastQuestion()), 1L, c.history());
            String rewritten = transformer.transform(Query.from(c.lastQuestion(), metadata))
                    .iterator().next().text();

            boolean rewrittenHit = hitPassage(c.snippet(), search(embed(embeddingModel, rewritten), rewritten));

            if (!rewritten.equals(c.lastQuestion())) changed++;
            if (base) baseHit++;
            if (rewrittenHit) rewriteHit++;

            System.out.printf("%-20s %-34s %-6s %-6s %s%n",
                    clip(c.lastQuestion(), 20), clip(rewritten, 34),
                    base ? "HIT" : "MISS", rewrittenHit ? "HIT" : "MISS", c.doc());
        }

        System.out.println("-".repeat(94));
        System.out.printf("基线（末轮原句直接检索）Passage Recall@%d = %d/%d%n", K, baseHit, cases.size());
        System.out.printf("改写后                      Passage Recall@%d = %d/%d%n", K, rewriteHit, cases.size());
        System.out.printf("查询文本确实被改写          = %d/%d%n", changed, cases.size());

        assertTrue(changed >= cases.size() - 1,
                "改写器几乎没改动 query，检查历史是否真的进了 Metadata.chatMemory");
        assertTrue(rewriteHit > baseHit,
                "改写后 Passage Recall 未超过直接检索（基线 " + baseHit + " vs 改写 " + rewriteHit + "）");
    }

    /**
     * 端到端冒烟：走 LiziAgent 真实链路（MongoChatMemory + DefaultRetrievalAugmentor + 流式 qwen-plus）。
     * 第二轮只说「那神经内科呢？」，正确回答应落在神经内科门诊楼层（6 层），
     * 而不是沿用第一轮的口腔科 7 层 —— 那就是改写没生效的典型症状。
     */
    @Test
    void smokeLiveMultiturn() {
        long memoryId = 9001L;
        String first = ask(memoryId, "口腔科的门诊在哪个位置？");
        String second = ask(memoryId, "那神经内科呢？");

        System.out.println();
        System.out.println("[冒烟] 第一轮回答 = " + first.replace("\n", " "));
        System.out.println("[冒烟] 第二轮回答 = " + second.replace("\n", " "));
        System.out.println("[冒烟] 第二轮提到 6 层 = " + second.contains("6"));

        assertFalse(first.isBlank(), "第一轮回答为空");
        assertFalse(second.isBlank(), "第二轮回答为空");
    }

    private String ask(long memoryId, String question) {
        List<String> chunks = liziAgent.chat(memoryId, question).collectList().block(Duration.ofSeconds(90));
        return chunks == null ? "" : String.join("", chunks);
    }

    private static Embedding embed(EmbeddingModel model, String text) {
        return model.embed(text).content();
    }

    /** 与线上同参：maxResults=K、minScore=0、动态元数据过滤（对改写后的 query 也照样生效） */
    private EmbeddingSearchResult<TextSegment> search(Embedding query, String queryText) {
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(K)
                .minScore(0.0);
        Filter filter = DYNAMIC_FILTER.apply(Query.from(queryText));
        if (filter != null) {
            builder.filter(filter);
        }
        return embeddingStore.search(builder.build());
    }

    private boolean hitPassage(String snippet, EmbeddingSearchResult<TextSegment> result) {
        return result.matches().stream().anyMatch(m -> m.embedded().text().contains(snippet));
    }

    /** 打印用：只在控制台里认，不参与断言 */
    private static String clip(String s, int max) {
        String oneLine = s.replace("\n", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private List<Case> loadEvalSet() throws Exception {
        List<Case> list = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("eval/rag-multiturn-eval-set.tsv")) {
            if (in == null) throw new IllegalStateException("找不到评测集 classpath:eval/rag-multiturn-eval-set.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length != 4) {
                    throw new IllegalStateException("多轮评测集格式错误（应为 4 列、制表符分隔）：" + line);
                }
                list.add(new Case(toHistory(parts[0]), parts[1], parts[2], parts[3]));
            }
        }
        return list;
    }

    /** 历史列：;; 分隔，交替为「用户 / AI」，以用户开头 */
    private static List<ChatMessage> toHistory(String raw) {
        List<ChatMessage> messages = new ArrayList<>();
        String[] turns = raw.split(";;");
        for (int i = 0; i < turns.length; i++) {
            messages.add(i % 2 == 0 ? UserMessage.from(turns[i]) : AiMessage.from(turns[i]));
        }
        return messages;
    }
}
