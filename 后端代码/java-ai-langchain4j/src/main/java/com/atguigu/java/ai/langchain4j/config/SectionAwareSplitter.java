package com.atguigu.java.ai.langchain4j.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知切块：按「小节」切，而不是按字数硬切。
 *
 * 为什么需要它（诊断证据见 KnowledgeChunkingDiagnosisTest）：
 * DocumentSplitters.recursive(300, 50) 把 `### 赵继志` 标题行和它下面的「擅长 : …」切成了两块，
 * 结果含答案的那一块里没有出现人名 → 向量里丢失实体信息 → 问「赵继志擅长什么」时排名靠后。
 *
 * 规则：
 * 1) 边界 = Markdown 标题行（^#{1,6}\s）或「伪标题行」（trim 后长度 ≤20 且以中文/英文冒号结尾，
 *    用于覆盖 医院信息.md / 科室信息.md 这类没有 # 的文档）。
 * 2) 每块正文前置**面包屑**（科室 > 小节 > 人名），使「擅长 : …」这类块自带人名 —— 每块自解释。
 * 3) 小节正文超过 MAX_CHARS 时用 DocumentSplitters.recursive 二次切，每段同样带面包屑；
 *    正文行原样保留（只 trim 判定用，不改写正文），保证评测集里的原文片段仍可按 contains 命中。
 *
 * metadata（除保留原有 file_name 等）：dept / section / entity / section_path / chunk_index。
 */
public class SectionAwareSplitter implements DocumentSplitter {

    /** 单块正文上限，量级与原 DocumentSplitters.recursive(300, 50) 保持一致 */
    private static final int MAX_CHARS = 300;

    /** 伪标题层级：比任何 Markdown 标题(1-6)都深，保证它只截断同级或更浅的标题，不会截断 ### 人名 */
    private static final int PSEUDO_LEVEL = 9;

    private static final String SEP = " > ";

    @Override
    public List<TextSegment> split(Document document) {
        String dept = deptOf(document);

        List<TextSegment> result = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<String> body = new ArrayList<>();

        for (String raw : document.text().split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int level = headingLevel(line);
            if (level == 0) {
                body.add(line);
                continue;
            }
            flush(result, document, dept, path, body);
            while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                levels.remove(levels.size() - 1);
                path.remove(path.size() - 1);
            }
            if (level == 1) {
                // H1 是「文档标题」（如「北京协和医口腔科详细介绍：」）不是小节，不进 path。
                // 排除它之后 path 的下标语义才干净：path[0]=小节，path[1]=实体。
                continue;
            }
            levels.add(level);
            path.add(line.trim().replaceFirst("^#+\\s*", ""));
        }
        flush(result, document, dept, path, body);
        return result;
    }

    /** 把当前累积的正文落成一个或多个块；正文为空直接丢弃，避免产生只有标题的垃圾块 */
    private void flush(List<TextSegment> result, Document document, String dept,
                       List<String> path, List<String> body) {
        String text = String.join("\n", body).trim();
        body.clear();
        if (text.isEmpty()) {
            return;
        }

        List<String> parts = new ArrayList<>();
        parts.add(dept);
        parts.addAll(path);
        String crumb = String.join(SEP, parts);

        int index = result.size();
        if (text.length() <= MAX_CHARS) {
            result.add(segment(crumb + "\n" + text, document, dept, path, crumb, index));
            return;
        }
        for (TextSegment sub : DocumentSplitters.recursive(MAX_CHARS, 0).split(Document.from(text))) {
            result.add(segment(crumb + "\n" + sub.text(), document, dept, path, crumb, index++));
        }
    }

    private TextSegment segment(String text, Document document, String dept,
                                List<String> path, String crumb, int index) {
        // H1 已在 split() 排除，path 的下标语义是干净的：
        //   path[0] = 小节（「专家团队」「科室地址」）
        //   path[1] = 实体（「赵继志」「崔丽英」）
        // 按「最后一级」取 entity 会取到 `### 详细介绍：` 这种四级描述行 —— 诊断器抓到过，故改按下标取。
        String section = path.isEmpty() ? dept : path.get(0);
        String entity = path.size() >= 2 ? path.get(1) : "";

        Metadata metadata = new Metadata(document.metadata().toMap());
        metadata.put("dept", dept);
        metadata.put("section", section);
        metadata.put("entity", entity);
        metadata.put("section_path", crumb);
        metadata.put("chunk_index", index);
        return TextSegment.from(text, metadata);
    }

    /** Markdown 标题返回 1-6，伪标题返回 PSEUDO_LEVEL，其余返回 0 */
    private static int headingLevel(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.charAt(0) == '#') {
            int n = 0;
            while (n < t.length() && t.charAt(n) == '#') {
                n++;
            }
            boolean valid = n <= 6 && (n == t.length() || t.charAt(n) == ' ');
            return valid ? Math.max(n, 1) : 0;
        }
        if (t.length() <= 20 && (t.endsWith("：") || t.endsWith(":"))) {
            return PSEUDO_LEVEL;
        }
        return 0;
    }

    private static String deptOf(Document document) {
        String name = document.metadata().getString(Document.FILE_NAME);
        if (name == null) {
            return "未知";
        }
        String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
        int dot = decoded.lastIndexOf('.');
        return dot > 0 ? decoded.substring(0, dot) : decoded;
    }
}
