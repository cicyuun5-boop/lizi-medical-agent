package com.atguigu.java.ai.langchain4j.config;

import com.atguigu.java.ai.langchain4j.store.MongoChatMemoryStore;
import com.atguigu.java.ai.langchain4j.store.SummarizingChatMemory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

@Configuration
public class LiziAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(LiziAgentConfig.class);

    /** 知识库文档目录（classpath 下），对应 src/main/resources/knowledge */
    private static final String KNOWLEDGE_DIR = "knowledge";

    @Autowired
    private MongoChatMemoryStore mongoChatMemoryStore;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Bean
    public ChatMemoryProvider chatMemoryProviderLizi(@Qualifier("qwenChatModel") ChatLanguageModel qwenChatModel) {

        //Phase 4：外面包一层 SummarizingChatMemory，把被窗口挤出去的历史并进摘要
        return memoryId ->
            new SummarizingChatMemory(
                    MessageWindowChatMemory.builder()
                            .id(memoryId)
                            .maxMessages(20)
                            .chatMemoryStore(mongoChatMemoryStore)
                            .build(),
                    mongoChatMemoryStore,
                    qwenChatModel);

    }

    /**
     * bean 名保持 contentRetrieverLiziPinecone 不变：
     * LiziAgent 是按 bean 名注入的，改内部实现不需要动 AI Service。
     */
    @Bean
    ContentRetriever contentRetrieverLiziPinecone(
            StringRedisTemplate stringRedisTemplate,
            @Value("${xiaozhi.rag.cache.ttl-seconds:3600}") long cacheTtlSeconds) {

        // 创建一个 EmbeddingStoreContentRetriever 对象，用于从嵌入存储中检索内容
        ContentRetriever embeddingRetriever = EmbeddingStoreContentRetriever
                .builder()
                // 设置用于生成嵌入向量的嵌入模型
                .embeddingModel(embeddingModel)
                // 指定要使用的嵌入存储
                .embeddingStore(embeddingStore)
                // 最多返回 3 条。别改回 1：答案段常常排不进 top-1（k=1 只有 18/30）。
                // 3 是实测拐点 —— k=3/minScore=0 时 Passage Recall 已达 30/30，k=5 也是 30/30，
                // 多取的 2 条只是白烧 token。度量过程见 RagRetrievalEvalTest，别凭感觉动这两个数。
                .maxResults(3)
                // 不设阈值过滤。0.8 过严会把正确段落直接滤掉（如「外科学系包含哪些科室？」空召回），
                // 实测 k=5 时 0.8→0.0 多救回 4 条；测出来 top-1 命中文档的正确率是 30/30，没有噪声问题。
                .minScore(0.0)
                // 动态元数据过滤：问句里点名了实体/科室时，先把候选集缩小再算向量距离。
                // 词表由 KnowledgeMetadataFilter 启动时从知识库解析得到（零 embedding 成本）。
                // 对没点名的问题返回 null = 维持全库检索，所以不会误杀。
                // 实测：召回上零增益（失分点是同文档块间排序，不是候选集太大），代价是每次检索
                // 仍要过一遍词典；留着是为了防跨科室串味 + 少返回无关块。见 ROADMAP Phase 3.5 Gate B。
                .dynamicFilter(KnowledgeMetadataFilter.dynamicFilter(KNOWLEDGE_DIR))
                // 构建最终的 EmbeddingStoreContentRetriever 实例
                .build();

        // 外面再包一层 Redis 缓存：同一问题第二次直接命中，跳过百炼 embedding + Pinecone 检索
        return new CachedContentRetriever(embeddingRetriever, stringRedisTemplate,
                Duration.ofSeconds(cacheTtlSeconds));
    }

    /**
     * 多轮查询改写：把对话历史喂给 CompressingQueryTransformer，让「那神经内科呢？」这类
     * 只含指代的末轮问句先改写成自包含 query（「神经内科的门诊在哪个位置？」）再检索。
     *
     * 与 contentRetrieverLiziPinecone 的关系：改写只动 query 文本，检索本身仍走上面那个
     * retriever（含 k=3 / minScore=0 / 动态元数据过滤 / Redis 缓存），所以 Gate A/B 的结论全部沿用。
     *
     * 成本：CompressingQueryTransformer 内部 `if (chatMemory.isEmpty()) return singletonList(query)`，
     * 首轮（无历史）零 LLM 调用；只有后续轮次多一次同步 qwen-max 往返（首字延迟 +1 跳）。
     *
     * 接管方式：LiziAgent 的 @AiService 把 contentRetriever 属性换成 retrievalAugmentor —— 二者互斥，
     * 都填时 retrievalAugmentor 生效。度量见 RagMultiturnEvalTest。
     */
    @Bean
    RetrievalAugmentor retrievalAugmentorLizi(ContentRetriever contentRetrieverLiziPinecone,
                                              @Qualifier("qwenChatModel") ChatLanguageModel qwenChatModel) {
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(new CompressingQueryTransformer(qwenChatModel))
                .contentRetriever(contentRetrieverLiziPinecone)
                .build();
    }

    /**
     * 知识入库：把 classpath:knowledge 下的文档切分、向量化后写入 Pinecone。
     *
     * 原先这里缺失了「写」的一半 —— 只有上面的 contentRetrieverLiziPinecone 在读，
     * 从来没有任何代码调用过 EmbeddingStoreIngestor.ingest()，所以检索到的一直是空索引。
     */
    @Bean
    ApplicationRunner knowledgeIngestRunner(
            @Value("${xiaozhi.knowledge.ingest-on-startup:true}") boolean ingestOnStartup) {

        return args -> {
            if (!ingestOnStartup) {
                log.info("xiaozhi.knowledge.ingest-on-startup=false，跳过知识入库");
                return;
            }

            // 显式指定解析器，不依赖 SPI 自动探测：knowledge 下只有 .md/.txt，
            // 而 classpath 上还有 tika 的 DocumentParserFactory，显式传入才能保证解析行为确定。
            List<Document> documents = ClassPathDocumentLoader.loadDocumentsRecursively(
                    KNOWLEDGE_DIR, new TextDocumentParser());
            if (documents.isEmpty()) {
                // 静默入库 0 篇等于没修好，直接失败比事后排查便宜
                throw new IllegalStateException(
                        "classpath:" + KNOWLEDGE_DIR + " 下没有读到任何知识库文档，请检查 src/main/resources/knowledge");
            }

            // 重灌前清空 namespace。不清的话每次启动都是「追加」，Pinecone 里会累积重复向量，
            // 白占 top-k 名额（实测 k=10 时 9 条是重复块）。清了之后本流程天然幂等。
            embeddingStore.removeAll();

            EmbeddingStoreIngestor.builder()
                    // 结构感知切块：按「小节」切并给每块加面包屑（科室 > 小节 > 人名），
                    // 而不是按字数硬切。原因见 SectionAwareSplitter 与 KnowledgeChunkingDiagnosisTest。
                    .documentSplitter(new SectionAwareSplitter())
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build()
                    .ingest(documents);

            log.info("知识入库完成：{} 篇文档已写入 Pinecone（已先清空 namespace）", documents.size());
        };
    }

}
