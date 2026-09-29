package com.atguigu.java.ai.langchain4j.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4：给「冷对话」加一层摘要记忆（聊久了开头被挤掉，还能记得住）。
 *
 * 【事实，javap 已证】{@code MessageWindowChatMemory.add()} 的顺序是
 * messages() →（若新消息是 SystemMessage 先移除旧的）→ list.add(新消息) → ensureCapacity 淘汰 → store.updateMessages。
 * 淘汰发生在写回之前 → 被挤出去的历史永远到不了 store，所以光改 store 层救不回来。
 * 唯一能截住它的位置，就是「淘汰」这一刻 —— 装饰器。
 *
 * 做法：包一只 MessageWindowChatMemory（下称 delegate）。每次 add() 前后对比窗口长度，
 * 算出这次被挤出去几条（就是 combined 最前面的那几条，SystemMessage 不参与），攒够 BATCH 条
 * 就调一次模型把它们并进 Mongo 里那条 summary；messages() 返回「摘要 + 当前窗口」。
 *
 * ponytail: pending 只放内存 —— 进程重启会丢掉最近最多 BATCH 条还没并进摘要的消息；
 * 摘要在 add() 里同步调模型合并；BATCH / maxMessages 都是拍的值，要调就改这里。
 */
public class SummarizingChatMemory implements ChatMemory {

    /** 攒够这么多条被淘汰的消息，才调一次模型合并摘要（省调用次数） */
    private static final int BATCH = 8;

    private final MessageWindowChatMemory delegate;
    private final MongoChatMemoryStore store;
    private final ChatLanguageModel summarizer;
    /** 已被淘汰、还没来得及并进摘要的消息 */
    private final List<ChatMessage> pending = new ArrayList<>();

    public SummarizingChatMemory(MessageWindowChatMemory delegate,
                                 MongoChatMemoryStore store,
                                 ChatLanguageModel summarizer) {
        this.delegate = delegate;
        this.store = store;
        this.summarizer = summarizer;
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> before = delegate.messages();
        delegate.add(message);
        List<ChatMessage> after = delegate.messages();

        // SystemMessage 是「替换旧的」而不是「新增」，长度变化不算淘汰，别误伤
        if (!(message instanceof SystemMessage)) {
            List<ChatMessage> combined = new ArrayList<>(before);
            combined.add(message);
            int evicted = combined.size() - after.size();
            int taken = 0;
            for (ChatMessage m : combined) {
                if (taken >= evicted) break;
                if (m instanceof SystemMessage) continue;
                pending.add(m);
                taken++;
            }
        }

        if (pending.size() >= BATCH) {
            mergeIntoSummary();
        }
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> window = delegate.messages();
        String summary = store.getSummary(id());
        if (summary == null || summary.isBlank()) {
            return window;
        }
        // 必须把摘要「并进」第一条 SystemMessage，而不是另外加一条：
        // DashScope 只认排在第一位的 system 消息，多塞的那条会被 QwenHelper 直接丢掉
        // （实测 WARN: The system message should be the first message. Drop existed messages）。
        String note = "\n\n[历史对话摘要，供你回忆之前聊过的内容]\n" + summary;
        List<ChatMessage> out = new ArrayList<>(window.size());
        boolean merged = false;
        for (ChatMessage m : window) {
            if (!merged && m instanceof SystemMessage s) {
                out.add(SystemMessage.from(s.text() + note));
                merged = true;
            } else {
                out.add(m);
            }
        }
        if (!merged) {
            out.add(0, SystemMessage.from("[历史对话摘要]\n" + summary));
        }
        return out;
    }

    @Override
    public void clear() {
        pending.clear();
        delegate.clear(); // 连带删掉 Mongo 里那条文档（summary 也随之消失）
    }

    private void mergeIntoSummary() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : pending) {
            sb.append(m).append('\n'); // ponytail: toString() 就行，只是喂给摘要模型
        }
        String old = store.getSummary(id());
        String prompt = "你是对话摘要助手。把「新增对话」里的关键信息并入「已有摘要」，输出更新后的完整摘要。"
                + "必须保留用户姓名、编号/卡号、过敏史、既往病史、已预约的科室与时间等一切事实；不要寒暄，不要解释。\n\n"
                + "已有摘要：\n" + (old == null || old.isBlank() ? "（无）" : old)
                + "\n\n新增对话：\n" + sb;
        store.updateSummary(id(), summarizer.chat(prompt));
        pending.clear();
    }
}
