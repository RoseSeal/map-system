package com.whut.map.listener.service.queue;
import com.whut.map.listener.entity.AisMessage;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class MessageQueue {

    // 使用MqttMessageEntity类型以接收7种不同的消息实体
    private final BlockingQueue<AisMessage> queue = new LinkedBlockingQueue<>();

    /**
     * 消息入队
     * @param message 待存入数据库的实体对象
     */
    public void enqueue(AisMessage message) {
        // offer 方法是非阻塞的，如果队列满了会立即返回false，对于无界队列基本不会发生
        this.queue.offer(message);
    }

    /**
     * 从队列中批量取出元素
     * @param collection 用于存放取出元素的集合
     * @param maxElements 最大取出的元素数量
     * @return 实际取出的元素数量
     */
    public int drainTo(Collection<AisMessage> collection, int maxElements) {
        return this.queue.drainTo(collection, maxElements);
    }

    /**
     * 获取当前队列大小
     * @return 队列中的元素数量
     */
    public int getSize() {
        return this.queue.size();
    }
}
