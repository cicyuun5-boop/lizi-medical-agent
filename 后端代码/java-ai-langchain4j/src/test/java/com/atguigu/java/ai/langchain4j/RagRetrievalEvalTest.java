package com.atguigu.java.ai.langchain4j;

import com.atguigu.java.ai.langchain4j.config.KnowledgeMetadataFilter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 3 —— RAG 检索质量评测（只度量，不改配置）。
 *
 * 口径：Passage Recall@k = 标准答案的「原文关键片段」是否出现在 top-k 结果的文本里。
 *       长文档切成多个 chunk 后「命中文档 != 命中含答案的那一段」，所以 Passage 口径比 Doc 口径严格，
 *       Doc Recall@k 只作对照。两个都记，才知道失败是「没召回文档」还是「召回了但段不对」。
 *
 * 为什么先建度量：没有可复现的数字，任何调参都是猜。query embedding 只算一次（30 次调用），
 * 之后想扫多少组 (k, minScore) 都是免费的，不用反复重启服务。
 *
 * 跑法（务必先停掉 8080 后端，避免 target/classes 写入冲突）：
 *   $env:JAVA_HOME='C:\Program Files\Java\jdk-21'
 *   mvn test -Dtest=RagRetrievalEvalTest
 *
 * 是否重灌由 application.properties 的 xiaozhi.knowledge.ingest-on-startup 决定（默认 false = 只度量）。
 * 改了知识库或切块逻辑后，把它临时置 true 跑一次本测试即可完成「清空 + 重灌 + 复测」，跑完记得改回 false。
 */
@SpringBootTest
class RagRetrievalEvalTest {

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    /** 一条评测样本：问题 / 期望文档 / 标准答案的原文关键片段 */
    private record Eval(String question, String doc, String snippet) {}

    /** 一组待扫的检索参数 */
    private record Combo(int k, double minScore, boolean filtered) {}

    /** 线上那份动态过滤器，直接用生产实现，保证评测与线上同源 */
    private static final Function<Query, Filter> DYNAMIC_FILTER =
            KnowledgeMetadataFilter.dynamicFilter("knowledge");

    /** 当前线上基线：Phase 3 的 EmbeddingStoreContentRetriever 就是这组参数（当时还没有动态过滤） */
    private static final Combo BASELINE = new Combo(1, 0.8, false);
    /** Phase 3 的候选：k 放大 + 放开 minScore */
    private static final Combo CANDIDATE = new Combo(5, 0.0, false);
    /** Gate B 候选：开动态过滤 + 小 k，目标是用更少的上下文块拿到更好的召回 */
    private static final Combo FILTERED = new Combo(1, 0.0, true);

    @Test
    void evalRetrieval() throws Exception {
        List<Eval> evals = loadEvalSet();
        assertEquals(30, evals.size(), "评测集条数应为 30，检查 classpath:eval/rag-eval-set.tsv");

        // 预计算 query embedding：只有这里花钱，后面换参数扫参不再调 embedding
        List<Embedding> queries = new ArrayList<>();
        for (Eval e : evals) {
            queries.add(embeddingModel.embed(e.question()).content());
        }

        // 探针：确认索引非空，并看清 metadata 里到底有什么（Doc 口径依赖 file_name）
        EmbeddingSearchResult<TextSegment> probe = search(queries.get(0), 1, 0.0, null);
        assertFalse(probe.matches().isEmpty(), "Pinecone 检索 0 条结果：索引为空，或 index/namespace/维度 配置不对");
        TextSegment sample = probe.matches().get(0).embedded();
        System.out.println("[探针] metadata keys = " + sample.metadata().toMap().keySet());
        System.out.println("[探针] file_name（需 URL 解码）= " + sample.metadata().getString("file_name"));
        System.out.println("[探针] 文本前80字 = "
                + sample.text().substring(0, Math.min(80, sample.text().length())).replace("\n", "\\n"));

        List<Combo> combos = List.of(
                BASELINE,
                new Combo(3, 0.8, false),
                new Combo(5, 0.8, false),
                new Combo(1, 0.0, false),
                new Combo(3, 0.0, false),
                CANDIDATE,
                FILTERED,
                new Combo(3, 0.0, true),
                new Combo(5, 0.0, true));

        // 每条 query 的过滤条件（null = 不过滤）。调的就是线上那份动态过滤器，评测与线上同源。
        List<Filter> filters = new ArrayList<>();
        for (Eval e : evals) {
            filters.add(DYNAMIC_FILTER.apply(Query.from(e.question())));
        }

        System.out.println();
        System.out.printf("%-4s %-9s %-7s %-14s %-12s %-10s %s%n",
                "k", "minScore", "过滤", "PassageRecall", "DocRecall", "平均返回", "重复块");
        System.out.println("-".repeat(78));

        List<String> baselineFails = new ArrayList<>();
        List<String> candidateFails = new ArrayList<>();
        List<String> filteredFails = new ArrayList<>();
        for (Combo c : combos) {
            int hitPassage = 0, hitDoc = 0, totalMatches = 0, distinctMatches = 0;
            List<String> fails = new ArrayList<>();

            for (int i = 0; i < evals.size(); i++) {
                Eval e = evals.get(i);
                List<EmbeddingMatch<TextSegment>> matches =
                        search(queries.get(i), c.k(), c.minScore(), c.filtered() ? filters.get(i) : null).matches();
                totalMatches += matches.size();

                Set<String> texts = new HashSet<>();
                boolean passage = false, doc = false;
                List<String> top = new ArrayList<>();
                for (EmbeddingMatch<TextSegment> m : matches) {
                    String text = m.embedded().text();
                    texts.add(text);
                    // Pinecone 存回来的 file_name 是 URL 编码的（%e5%8c%bb... = 医院信息.md）。
                    // 不解码 DocRecall 会全是 0 —— 那是度量 bug，不是检索失败。
                    String file = URLDecoder.decode(m.embedded().metadata().getString("file_name"), StandardCharsets.UTF_8);
                    top.add(String.format("%.3f:%s", m.score(), file));
                    if (text.contains(e.snippet())) passage = true;
                    if (e.doc().equals(file)) doc = true;
                }
                // 同一条结果列表里出现完全相同的文本 = Pinecone 里有重复向量，会白占 top-k 名额
                distinctMatches += texts.size();
                if (passage) hitPassage++;
                if (doc) hitDoc++;
                if (!passage) {
                    fails.add(String.format("  MISS | %s | 期望=%s（文档已命中=%s） | top=%s",
                            e.question(), e.doc(), doc, top));
                }
            }

            System.out.printf("%-4d %-9.1f %-7s %-14s %-12s %-10.2f %d%n", c.k(), c.minScore(),
                    c.filtered() ? "开" : "关",
                    hitPassage + "/" + evals.size(), hitDoc + "/" + evals.size(),
                    totalMatches / (double) evals.size(), totalMatches - distinctMatches);

            if (BASELINE.equals(c)) baselineFails = fails;
            if (CANDIDATE.equals(c)) candidateFails = fails;
            if (FILTERED.equals(c)) filteredFails = fails;
        }

        printFails("基线 k=1, minScore=0.8（Phase 3 之前）", baselineFails);
        printFails("候选 k=5, minScore=0.0（Phase 3）", candidateFails);
        printFails("Gate B 候选 k=1, minScore=0.0 + 动态过滤", filteredFails);

        printFilterSubset(evals, queries, filters);
    }

    /**
     * 过滤只对「问句点名了实体/科室」的那部分生效。整体 30 条里大半句子里没有人名科室名、
     * 走的还是全库检索，会把提升稀释掉。所以单独统计真正加了过滤的子集。
     */
    private void printFilterSubset(List<Eval> evals, List<Embedding> queries, List<Filter> filters) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) != null) {
                idx.add(i);
            }
        }

        System.out.println();
        System.out.println("=== 只统计「问句命中词典、会真正加过滤」的 " + idx.size() + " / " + evals.size() + " 条 ===");
        System.out.printf("%-4s %-12s %-12s%n", "k", "不过滤", "动态过滤");
        System.out.println("-".repeat(32));
        for (int k : List.of(1, 2, 3, 5)) {
            int plainHit = 0, filteredHit = 0;
            for (int i : idx) {
                Eval e = evals.get(i);
                if (hitPassage(e, search(queries.get(i), k, 0.0, null))) plainHit++;
                if (hitPassage(e, search(queries.get(i), k, 0.0, filters.get(i)))) filteredHit++;
            }
            System.out.printf("%-4d %-12s %-12s%n", k, plainHit + "/" + idx.size(), filteredHit + "/" + idx.size());
        }

        System.out.println();
        System.out.println("=== 逐条：问句命中了什么过滤条件 ===");
        for (int i = 0; i < evals.size(); i++) {
            Filter f = filters.get(i);
            System.out.printf("  %-34s %s%n", evals.get(i).question(), f == null ? "（不过滤）" : f);
        }
    }

    private boolean hitPassage(Eval e, EmbeddingSearchResult<TextSegment> result) {
        return result.matches().stream().anyMatch(m -> m.embedded().text().contains(e.snippet()));
    }

    private void printFails(String title, List<String> fails) {
        System.out.println();
        System.out.println("=== " + title + " 未命中 Passage 的样本 ===");
        if (fails.isEmpty()) {
            System.out.println("  （无：已全部命中）");
        } else {
            fails.forEach(System.out::println);
        }
    }

    private EmbeddingSearchResult<TextSegment> search(Embedding query, int k, double minScore, Filter filter) {
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(k)
                .minScore(minScore);
        if (filter != null) {
            builder.filter(filter);
        }
        return embeddingStore.search(builder.build());
    }

    private List<Eval> loadEvalSet() throws Exception {
        List<Eval> list = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("eval/rag-eval-set.tsv")) {
            if (in == null) throw new IllegalStateException("找不到评测集 classpath:eval/rag-eval-set.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length != 3) throw new IllegalStateException("评测集格式错误（应为 3 列、制表符分隔）：" + line);
                list.add(new Eval(parts[0], parts[1], parts[2]));
            }
        }
        return list;
    }
}
