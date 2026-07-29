package io.github.aigoodle.workflow.chat;

import java.util.function.Consumer;

/**
 * 工作流引擎里贯穿全图的"下行 token 通道"。类比老 spring-agent-start 的
 * {@code com.goodidea.chat.ChatPenetrate}：LLM 节点执行时探测 sink 是否
 * 处于流模式，决定用 {@code spec.call()} 还是 {@code spec.stream()}；
 * ANSWER 节点消费上游 {@link ChatFluxHandle} 时把 token 逐条推给 sink。
 * <p>
 * 单一 {@link Consumer} 抽象让上层可以自由决定 token 去处：
 * <ul>
 *   <li>{@code io.github.aigoodle.completion.service.WorkflowChatGenerator} 把
 *       每 token 翻译成 OpenAI {@code message} chunk 后打进 SSE emit；</li>
 *   <li>测试 / 阻塞调用方可以传一个 {@code StringBuilder::append} 收集全部 token；</li>
 *   <li>{@code null} → 完全走阻塞路径（LLM 用 {@code .call()}），退化到原语义。</li>
 * </ul>
 * <p>
 * <b>线程模型</b>：工作流引擎用 virtual-thread-per-task 调度节点，多个节点
 * 可能并发 push；{@link #push(String)} 内部加锁串行化写入，避免 token 交叉。
 */
public final class ChatStreamSink {

    private final Consumer<String> delegate;
    private final StringBuilder accumulator = new StringBuilder();
    private final Object lock = new Object();
    private volatile boolean done = false;

    public ChatStreamSink(Consumer<String> delegate) {
        this.delegate = delegate;
    }

    /** {@code true} = 有 SSE 下游订阅；LLM/ANSWER 会走 {@code stream().content()}。 */
    public boolean isStreaming() {
        return delegate != null;
    }

    /**
     * 推送一段 token。缓冲区始终累积（供最终从 outputs 里读整段答案）；
     * delegate 非空时同步转发出去。异常吞掉——SSE 消费方断连不能把整个
     * 工作流拖崩。
     */
    public void push(String delta) {
        if (delta == null || delta.isEmpty() || done) return;
        synchronized (lock) {
            accumulator.append(delta);
            if (delegate != null) {
                try {
                    delegate.accept(delta);
                } catch (Exception ignore) {
                    // Streaming 是 best-effort，consumer 挂了不影响工作流本身。
                }
            }
        }
    }

    /** 标记 sink 关闭——后续 {@link #push} 直接丢弃。用于工作流失败提前止血。 */
    public void close() {
        done = true;
    }

    /** 迄今为止累积的完整文本。用于工作流结束后把整答案写进 outputs / 历史。 */
    public String accumulated() {
        synchronized (lock) {
            return accumulator.toString();
        }
    }
}
