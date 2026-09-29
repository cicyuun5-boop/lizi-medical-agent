package com.atguigu.java.ai.langchain4j;

import com.atguigu.java.ai.langchain4j.config.KnowledgeMetadataFilter;
import com.atguigu.java.ai.langchain4j.config.SectionAwareSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 纯本地诊断：SectionAwareSplitter 到底把知识库切成了什么样。
 * 不连 Spring、不连 Pinecone、不花钱，1 秒跑完。
 *
 * 要验证的假设：以人名/科室为实体的问句，含答案的那一块里**必须出现这个实体名**（块自解释）。
 * 改造前用 DocumentSplitters.recursive(300,50) 时，7 条「擅长什么」探针里有 4 条答案块不含人名。
 */
class KnowledgeChunkingDiagnosisTest {

    private record Probe(String question, String doc, String snippet, String entity) {}

    private static final List<Probe> PROBES = List.of(
            new Probe("赵继志擅长什么？", "口腔科.md", "口腔颌面部肿瘤、畸形的诊治及整形美容修复", "赵继志"),
            new Probe("赖钦声擅长哪些疾病？", "口腔科.md", "涎腺疾病、颞下颌关节疾病", "赖钦声"),
            new Probe("万阔擅长什么？", "口腔科.md", "无痛牙科治疗、牙科恐惧治疗", "万阔"),
            new Probe("王巧璋提出了什么学说？", "口腔科.md", "糖原的内因致龋学说", "王巧璋"),
            new Probe("口腔科的首任主任是谁？", "口腔科.md", "Bert Anderson任首任主任", "Bert Anderson"),
            new Probe("口腔科的门诊在哪个位置？", "口腔科.md", "东院门诊楼7层", "科室地址"),
            new Probe("崔丽英擅长什么？", "神经内科.md", "运动神经元病、周围神经病、肌肉病", "崔丽英"),
            new Probe("朱以诚擅长什么？", "神经内科.md", "血管性帕金森，血管性认知功能障碍", "朱以诚"),
            new Probe("彭斌擅长什么？", "神经内科.md", "脑血管病及神经系统危重疾病", "彭斌"),
            new Probe("神经内科的门诊在哪个位置？", "神经内科.md", "东院门诊楼6层", "科室地址"),
            new Probe("神经内科近5年承担了多少科研课题？", "神经内科.md", "近5年来共承担科研课题40余项", "科研课题"),
            new Probe("外科学系包含哪些科室？", "科室信息.md", "基本外科、骨科、心外科", "外科学系"),
            new Probe("东单院区的地址是什么？", "医院信息.md", "北京市东城区帅府园一号", "东单"),
            new Probe("门诊开放时间是什么？", "医院信息.md", "8:00 - 17:30", "门诊")
    );

    @Test
    void diagnose() {
        List<Document> docs = ClassPathDocumentLoader.loadDocumentsRecursively(
                "knowledge", new TextDocumentParser());

        Map<String, List<TextSegment>> byDoc = new LinkedHashMap<>();
        for (Document d : docs) {
            String raw = d.metadata().getString(Document.FILE_NAME);
            String fileName = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            byDoc.put(fileName, new SectionAwareSplitter().split(d));
        }

        System.out.println("\n===== 1. 文档 / chunk 总数 =====");
        byDoc.forEach((f, cs) -> System.out.printf("%-18s %2d 块%n", f, cs.size()));

        System.out.println("\n===== 2. 探针：含答案片段的 chunk 里，有没有出现实体名？ =====");
        System.out.printf("%-32s %-14s %-10s %-8s %s%n", "问题", "文档", "块编号", "含实体", "块首 55 字");
        System.out.println("-".repeat(150));
        for (Probe p : PROBES) {
            List<TextSegment> chunks = byDoc.get(p.doc());
            if (chunks == null) {
                System.out.printf("%-32s %-14s %s%n", p.question(), p.doc(), "!! 文档未找到");
                continue;
            }
            boolean anyHit = false;
            for (int i = 0; i < chunks.size(); i++) {
                String t = chunks.get(i).text();
                if (!t.contains(p.snippet())) continue;
                anyHit = true;
                System.out.printf("%-32s %-14s #%02d/%-6d %-8s %s%n",
                        p.question(), p.doc(), i, chunks.size() - 1,
                        t.contains(p.entity()) ? "是" : "否",
                        head(t.replace("\n", "⏎"), 55));
            }
            if (!anyHit) {
                System.out.printf("%-32s %-14s %s%n", p.question(), p.doc(), "!! 没有任何 chunk 含该片段");
            }
        }

        System.out.println("\n===== 3. 口腔科.md 全部 chunk 概览（关注：`### 人名` 是否和其介绍同块）=====");
        dump(byDoc.get("口腔科.md"), "赵继志");

        System.out.println("\n===== 4. 神经内科.md 全部 chunk 概览 =====");
        dump(byDoc.get("神经内科.md"), "崔丽英");

        System.out.println("\n===== 5. 元数据标签（dept / section / entity / section_path / chunk_index）=====");
        byDoc.forEach((f, cs) -> {
            long withEntity = cs.stream().filter(c -> !c.metadata().getString("entity").isEmpty()).count();
            System.out.printf("%-18s %2d 块 | dept=%-8s section=%-14s chunk_index=0..%d%n",
                    f, cs.size(), cs.get(0).metadata().getString("dept"),
                    cs.get(0).metadata().getString("section"), cs.size() - 1);
            System.out.printf("%-18s   entity 非空 %d 块，取值：%s%n", "", withEntity,
                    cs.stream().map(c -> c.metadata().getString("entity"))
                            .filter(e -> !e.isEmpty()).distinct().toList());
        });

        // Gate B 自检：词典与过滤命中最容易出错（长词优先、dept 误命中），
        // 这里是零成本的最后一道闸，跑 Pinecone 之前先看这一节。
        System.out.println("\n===== 6. Gate B 过滤器：30 条评测 query 命中什么 =====");
        Function<Query, Filter> filterOf = KnowledgeMetadataFilter.dynamicFilter("knowledge");
        int hit = 0;
        for (String question : evalQuestions()) {
            Filter f = filterOf.apply(Query.from(question));
            if (f != null) {
                hit++;
            }
            System.out.printf("%-42s %s%n", question, f == null ? "（不过滤）" : f);
        }
        System.out.printf("命中词典（真正加过滤）%d / %d%n", hit, evalQuestions().size());
    }

    /** 读评测集第一列（问题），只用于自检命中率 */
    private static List<String> evalQuestions() {
        List<String> questions = new ArrayList<>();
        try (InputStream in = KnowledgeChunkingDiagnosisTest.class.getClassLoader()
                .getResourceAsStream("eval/rag-eval-set.tsv")) {
            if (in == null) throw new IllegalStateException("找不到 classpath:eval/rag-eval-set.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                questions.add(line.split("\t")[0]);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return questions;
    }

    private static void dump(List<TextSegment> chunks, String watch) {
        if (chunks == null) {
            System.out.println("（未找到该文档）");
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            String t = chunks.get(i).text();
            System.out.printf("[%02d] len=%-4d 含标题=%-3s 含「%s」=%-3s | %s%n",
                    i, t.length(),
                    t.contains("###") ? "有" : "无",
                    watch, t.contains(watch) ? "有" : "无",
                    head(t.replace("\n", "⏎"), 72));
        }
    }

    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
