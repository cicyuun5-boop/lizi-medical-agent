package com.atguigu.java.ai.langchain4j;

import com.atguigu.java.ai.langchain4j.assistant.LiziAgent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 Gate D —— 冷对话记忆评测（长会话还记得开头吗？）。
 *
 * 要证明的事：{@code MessageWindowChatMemory} 窗口固定 20 条（≈10 轮），聊久了会把开头淘汰掉，
 * 且淘汰发生在写回 Mongo 之前（{@code javap} 已证）→ 第 1 轮埋的信息彻底丢失，末轮追问必然答不出。
 * 尺子只问一件事：聊了 N 轮之后，模型还记不记得第 1 轮说的过敏史。
 *
 * 跑法（务必先停掉 8080 后端，避免 target/classes 写入冲突）：
 *   $env:JAVA_HOME='C:\Program Files\Java\jdk-21'
 *   mvn test -Dtest=Gate4MemoryEvalTest                       # 默认 50 轮（ROADMAP 验收口径）
 *   mvn test -Dtest=Gate4MemoryEvalTest -Dgate4.rounds=20     # 开发期降成本
 *
 * 执行顺序（先建尺子再改代码）：先跑一次拿基线（期望断言失败 = 现在确实答不出，且尺子有效），
 * 实现 SummarizingChatMemory 后再跑一次，用同一把尺子对比。
 *
 * 成本：每轮 = 1 次 RAG（重复问题命中 Redis 缓存）+ 1 次 qwen-plus 流式回答；N 轮 ≈ N 次对话调用。
 */
@SpringBootTest
class Gate4MemoryEvalTest {

    @Autowired
    private LiziAgent liziAgent;

    /** 与 LiziAgentConfig 同源：拿到生产用的那只记忆对象（含 clear/messages） */
    @Autowired
    @Qualifier("chatMemoryProviderLizi")
    private ChatMemoryProvider chatMemoryProviderLizi;

    /**
     * 埋点用的唯一串 = 病历编号。必须「模型猜不出、知识库没有、缓存里也没有」，
     * 否则模型瞎编一个最常见的答案（如「青霉素」）就会让断言恒真、尺子失效。
     */
    private static final String PROBE = "ZZQ9911";

    /** 填充轮用的问题，循环使用；内容不重要，作用只是把窗口撑爆 */
    private static final String[] FILL = {
            "口腔科的门诊在哪个位置？",
            "神经内科的门诊在哪个位置？",
            "医院的地址在哪里？",
            "急诊科的电话是多少？",
            "朱以诚擅长什么？",
            "神经内科成立于哪一年？",
            "门诊时间是怎么安排的？",
            "体检中心在哪里？",
            "儿科门诊几点开始？",
            "口腔科的联系电话是多少？",
    };

    @Test
    void evalColdMemory() {
        long memoryId = 9100L;
        chatMemoryProviderLizi.get(memoryId).clear();

        int rounds = Integer.getInteger("gate4.rounds", 50);
        String firstQuestion = "你好，我叫李小明，我的病历编号是 " + PROBE
                + "，请务必记住，但不要在回答里重复这个编号。";
        String recallQuestion = "我刚才告诉你的病历编号是多少？只回答编号本身，不要解释。";

        String firstAnswer = ask(memoryId, firstQuestion);
        System.out.println();
        System.out.println("[Gate D] 第 1 轮埋点回答 = " + clip(firstAnswer, 40));

        for (int i = 2; i <= rounds; i++) {
            String question = FILL[(i - 2) % FILL.length];
            String answer = ask(memoryId, question);
            // 诊断：窗口实际长度 + 第 1 轮那句还在不在（若长度恒 ≤20 且含第 1 轮 → 淘汰没按预期发生）
            List<ChatMessage> w = chatMemoryProviderLizi.get(memoryId).messages();
            boolean still = w.stream().anyMatch(m -> m.toString().contains(PROBE));
            System.out.printf("[Gate D] 第 %d 轮填充：%s -> %s | 窗口=%d 含第1轮=%s%n",
                    i, question, clip(answer, 30), w.size(), still);
        }

        // 证据 1：当前窗口里还有没有第 1 轮那句（基线里应为 false —— 已被淘汰）
        List<ChatMessage> window = chatMemoryProviderLizi.get(memoryId).messages();
        boolean firstStillInWindow = window.stream().anyMatch(m -> m.toString().contains(PROBE));

        // 诊断：把窗口逐条打出来（类型 + 是否含埋点 + 末尾 40 字）
        System.out.println("[Gate D] 末轮窗口逐条：");
        for (int i = 0; i < window.size(); i++) {
            String t = window.get(i).toString();
            System.out.printf("    #%d %s 含埋点=%s | 尾40=%s%n", i,
                    window.get(i).getClass().getSimpleName(), t.contains(PROBE),
                    clip(t.substring(Math.max(0, t.length() - 40)), 40));
        }

        // 尺子有效性自检：埋点只应出现在「注入的摘要」那一条里。
        // 若窗口自己也命中（模型复述了编号、被存进记忆），这次测量就无效 —— 必须报错，不能算通过。
        long probeInWindow = window.stream().filter(m -> m.toString().contains(PROBE)).count();
        System.out.println("[Gate D] 命中埋点的消息条数（摘要 1 条 = 干净）= " + probeInWindow);
        assertTrue(probeInWindow <= 1,
                "埋点仍留在窗口里（模型自己复述了编号），本次测量无效；条数 = " + probeInWindow);

        // 证据 2：末轮追问还能不能答出
        String recall = ask(memoryId, recallQuestion);

        System.out.println();
        System.out.println("[Gate D] 轮数 = " + rounds + "，末轮窗口消息数 = " + window.size());
        System.out.println("[Gate D] 第 1 轮关键信息仍在窗口内 = " + firstStillInWindow);
        System.out.println("[Gate D] 追问「病历编号多少」的回答 = " + clip(recall, 60));
        System.out.println("[Gate D] 回答命中埋点 " + PROBE + " = " + recall.contains(PROBE));

        assertTrue(recall.contains(PROBE),
                rounds + " 轮后仍应记得第 1 轮的病历编号 " + PROBE + "；实际回答 = " + recall);
    }

    private String ask(long memoryId, String question) {
        List<String> chunks = liziAgent.chat(memoryId, question).collectList().block(Duration.ofSeconds(90));
        return chunks == null ? "" : String.join("", chunks);
    }

    /** 打印用：只在控制台里认，不参与断言 */
    private static String clip(String s, int max) {
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
