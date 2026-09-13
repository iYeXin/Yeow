package yeow;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MsgQueue {
    public final BlockingQueue<String> toJava = new LinkedBlockingQueue<>();
    /** Producer -> JS-thread messages: raw objects (encoded to binary by the JS thread) or ready JSON strings. */
    private final BlockingQueue<Object> toJs = new LinkedBlockingQueue<>();

    public void sendJava(String json) { toJava.add(json); }
    public String pollJava() { return toJava.poll(); }

    public void sendJs(Object message) { toJs.add(message); }

    /**
     * 消息驱动的消费模型（原子性由 BlockingQueue 保证）：
     * 无消息时阻塞等待（消息循环"未运行"态，零轮询）；收到消息即返回。
     */
    public Object takeJs() throws InterruptedException {
        return toJs.take();
    }

    /** 处理完一条后非阻塞取剩余消息（有剩余继续处理，无剩余回到 takeJs 阻塞）。 */
    public Object pollJs() {
        return toJs.poll();
    }

    public void clear() { toJava.clear(); toJs.clear(); }
}
