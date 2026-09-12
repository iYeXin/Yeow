package yeow;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MsgQueue {
    public final BlockingQueue<String> toJava = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> toJs = new LinkedBlockingQueue<>();

    public void sendJava(String json) { toJava.add(json); }
    public String pollJava() { return toJava.poll(); }

    public void sendJs(String json) { toJs.add(json); }

    /**
     * 消息驱动的消费模型（原子性由 BlockingQueue 保证）：
     * 无消息时阻塞等待（消息循环"未运行"态，零轮询）；收到消息即返回
     * （入队与唤醒是原子的——发送方 add 后消费者 take 必定可见）。
     */
    public String takeJs() throws InterruptedException {
        return toJs.take();
    }

    /** 处理完一条后非阻塞取剩余消息（有剩余继续处理，无剩余回到 takeJs 阻塞）。 */
    public String pollJs() {
        return toJs.poll();
    }

    public void clear() { toJava.clear(); toJs.clear(); }
}
