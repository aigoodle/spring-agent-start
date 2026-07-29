package io.github.aigoodle.workflow.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LLM 节点在流式模式下的 output 类型。类比老 spring-agent-start 的
 * {@code com.goodidea.chat.ChatFluxData}：把 {@code spec.stream().content()}
 * 返回的冷 Flux 包成一份可多订阅、可阻塞取整段结果的"共享句柄"。
 * <p>
 * 关键语义：
 * <ul>
 *   <li>{@link #stream()} 返回 {@code Flux.cache()} 之后的可重放流 —— 首个
 *       订阅者触发实际的 LLM 调用（Spring AI advisor 链只走一次），后续订阅者
 *       从缓存重放。</li>
 *   <li>{@link #getFutureMessage()} 阻塞直到流完成，返回累积文本。用于
 *       {@code {{#llm.text#}}} 模板占位符的透明解析。</li>
 *   <li>{@link #toString()} 直接委托 {@link #getFutureMessage()} —— 这样
 *       {@link io.github.aigoodle.workflow.variable.VariableResolver} 完全
 *       不用感知句柄类型，做 {@code String.valueOf(value)} 就自动阻塞取值。</li>
 * </ul>
 * <p>
 * <b>为什么是 cache 不是 share</b>：Spring AI 1.1.2 里 {@code spec.stream()} 走
 * {@code DefaultAroundAdvisorChain}，advisor 链的迭代器是一次性的。share() +
 * refCount 在最后一个订阅者取消后会断开源，下一个订阅者触发重连时会重跑
 * advisor 链——迭代器早就耗尽了，直接抛
 * {@code "No StreamAdvisors available to execute"}。cache() 只跑一次上游，
 * 之后所有订阅者都从缓存重放，天然规避这个 bug；副作用是要等第一个订阅者
 * 出现才开始拉，而这正好符合我们的意图（ANSWER 才是"该看到实时 token"的
 * 那个消费者，节点侧的 Jackson 序列化不该抢跑）。
 * <p>
 * <b>Jackson 陷阱</b>：workflow 引擎在每步完成后会把 outputs 序列化到
 * step_finished SSE 事件里。{@code getFutureMessage()} 被 Jackson 识别成
 * bean 属性 {@code futureMessage} 后会阻塞并抢跑 cache，把本该给 ANSWER
 * 的实时 token 全部提前吃掉。用 {@link JsonValue @JsonValue} 在
 * {@link #snapshot()} 上把整个 handle 序列化成一个非阻塞的字符串快照，
 * 阻塞路径的 getter 全部 {@link JsonIgnore @JsonIgnore}。
 */
public final class ChatFluxHandle {

    private final Flux<String> shared;
    private final StringBuilder buffer = new StringBuilder();
    private final CountDownLatch completed = new CountDownLatch(1);
    /** {@link #getFutureMessage} 的兜底超时——避免上游卡死拖着整个链路。 */
    private static final long FUTURE_TIMEOUT_SEC = 300;
    private volatile boolean cachedComplete = false;
    private volatile Throwable failure;

    public ChatFluxHandle(Flux<String> rawStream) {
        // 用 cache() 而不是 share()：Spring AI 1.1.2 的 spec.stream() 走
        // DefaultAroundAdvisorChain，advisor 迭代器只能走一次；share() + refCount
        // 在第一个订阅者完成后会断开源，第二个订阅者会触发重新连接 → 迭代器已耗尽
        // → "No StreamAdvisors available to execute"。cache() 首个订阅者触发一次
        // 上游拉取并把所有元素缓存，之后任意订阅者都从缓存重放，绝不会重跑 advisor
        // 链。副作用是要等第一个订阅者出现才开始拉——这正是我们想要的（ANSWER 是
        // 首订阅者时能拿到真正的实时 token；Jackson 走 {@link #snapshot()} 序列化
        // 不会触发订阅）。
        // doOnNext 仍挂在 cache 之前，保证累积器命中每个 token；模板占位符走
        // {@link #getFutureMessage()} 阻塞取整段时也从缓存里拿。
        this.shared = rawStream
                .doOnNext(token -> {
                    if (token == null) return;
                    synchronized (buffer) {
                        buffer.append(token);
                    }
                })
                .doOnError(err -> {
                    this.failure = err;
                    cachedComplete = true;
                    completed.countDown();
                })
                .doOnComplete(() -> {
                    cachedComplete = true;
                    completed.countDown();
                })
                .cache();
    }

    /** 共享流。用于 ANSWER 节点订阅式地把 token 灌到 SSE。 */
    @JsonIgnore
    public Flux<String> stream() {
        return shared;
    }

    /**
     * 阻塞取整段。用在需要完整文本的下游场景：TEMPLATE_TRANSFORM /
     * IF_ELSE / 结构化解析 / 模板占位符渲染 等。
     * <p>
     * 若尚未有任何 subscriber，本方法会自己触发一次订阅（用无操作 sink）
     * 让 upstream 开始跑；等 latch countDown 后返回。这样即使 workflow
     * 里没有 ANSWER 节点，上游 LLM 也不会被冷冻。
     * <p>
     * {@link JsonIgnore} 关键：默认情况下 Jackson 会把这个方法当 bean 属性
     * {@code futureMessage} 序列化。工作流的 step_finished SSE 事件里
     * outputs.text = 一个 ChatFluxHandle，Jackson 一旦调这个 getter 就会
     * 触发 blockLast() 抢跑上游流；抢到之后 cache() 满了，但真正的消费者
     * （ANSWER 节点）拿到的是"缓存重放的历史 token"，全部一次性到达用户
     * 面前——流式效果消失。用 {@link #snapshot()} + {@link JsonValue}
     * 让 Jackson 拿一个"不阻塞的当前快照"，事件生成瞬间可能是空串，但
     * 这正是我们想要的（真正的答案通过 message chunk 事件流式送达）。
     */
    @JsonIgnore
    public String getFutureMessage() {
        if (cachedComplete) {
            return snapshot();
        }
        // 用 blockLast 触发 subscription + 等待 —— 已 shared 所以 idempotent。
        try {
            shared.blockLast(java.time.Duration.ofSeconds(FUTURE_TIMEOUT_SEC));
        } catch (Exception e) {
            // fall through to latch-based wait; blockLast 有时因为已经 share 出去了
            // 报 IllegalStateException("Duplicate subscription")，那种情况用 latch 兜底。
        }
        try {
            if (!completed.await(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待 LLM 流式响应超时 (" + FUTURE_TIMEOUT_SEC + "s)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
        if (failure != null) {
            throw failure instanceof RuntimeException re ? re : new RuntimeException(failure);
        }
        return snapshot();
    }

    /**
     * 透明委托到 {@link #getFutureMessage()} —— 让 {@code String.valueOf(handle)}
     * 自动阻塞取整段文本，模板渲染层零感知。
     */
    @Override
    public String toString() {
        return getFutureMessage();
    }

    /**
     * 快照当前累积器内容。不阻塞——用于日志 / 调试。也是 Jackson 的序列化
     * 出口（{@link JsonValue}）：把整个 handle 序列化成一个纯字符串，避免
     * 事件生成路径意外触发上游订阅。
     */
    @JsonValue
    public String snapshot() {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    /** 流是否已终结（正常完成或异常）。 */
    @JsonIgnore
    public boolean isComplete() {
        return cachedComplete;
    }
}
