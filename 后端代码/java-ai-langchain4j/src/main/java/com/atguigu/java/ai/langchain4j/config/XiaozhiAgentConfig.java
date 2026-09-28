package com.atguigu.java.ai.langchain4j.config;

import com.atguigu.java.ai.langchain4j.store.MongoChatMemoryStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class XiaozhiAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(XiaozhiAgentConfig.class);

    /** 知识库文档目录（classpath 下），对应 src/main/resources/knowledge */
    private static final String KNOWLEDGE_DIR = "knowledge";

    @Autowired
    private MongoChatMemoryStore mongoChatMemoryStore;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Bean
    public ChatMemoryProvider chatMemoryProviderXiaozhi() {

        return memoryId ->
            MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(20)
                    .chatMemoryStore(mongoChatMemoryStore)
                    .build();

    }

    @Bean
    ContentRetriever contentRetrieverXiaozhiPincone() {

        // 创建一个 EmbeddingStoreContentRetriever 对象，用于从嵌入存储中检索内容
        return EmbeddingStoreContentRetriever
                .builder()
                // 设置用于生成嵌入向量的嵌入模型
                .embeddingModel(embeddingModel)
                // 指定要使用的嵌入存储
                .embeddingStore(embeddingStore)
                // 设置最大检索结果数量，这里表示最多返回 1 条匹配结果
                .maxResults(1)
                // 设置最小得分阈值，只有得分大于等于 0.8 的结果才会被返回
                .minScore(0.8)
                // 构建最终的 EmbeddingStoreContentRetriever 实例
                .build();
    }

    /**
     * 知识入库：把 classpath:knowledge 下的文档切分、向量化后写入 Pinecone。
     *
     * 原先这里缺失了「写」的一半 —— 只有上面的 contentRetrieverXiaozhiPincone 在读，
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

            EmbeddingStoreIngestor.builder()
                    .documentSplitter(DocumentSplitters.recursive(300, 50))
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build()
                    .ingest(documents);

            log.info("知识入库完成：{} 篇文档已写入 Pinecone", documents.size());

            // ponytail: 每次启动都会重新向量化并写入，反复重启会在 Pinecone 里累积重复向量。
            // 首次入库成功后建议把 xiaozhi.knowledge.ingest-on-startup 置为 false；
            // 升级路径：给每篇文档写稳定的 documentId，按 id 覆盖写入做到幂等。
        };
    }

}
