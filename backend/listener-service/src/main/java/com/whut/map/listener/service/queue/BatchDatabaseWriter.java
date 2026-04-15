package com.whut.map.listener.service.queue;

import com.whut.map.listener.entity.AisMessage;
import com.whut.map.listener.repository.AisMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BatchDatabaseWriter {

    private static final Logger logger = LoggerFactory.getLogger(BatchDatabaseWriter.class);

    private final MessageQueue messageQueue;

    // 使用一个Map来存储实体类与对应的Repository的映射关系
    private final Map<Class<?>, JpaRepository> repositoryMap;

    // 从配置文件读取批量大小，提供一个默认值100
    @Value("${database.batch.size:100}")
    private int batchSize;

    // 注入所有JpaRepository的实现
    @Autowired
    public BatchDatabaseWriter(
            MessageQueue messageQueue,
            AisMessageRepository aisMessageRepository
    ) {
        this.messageQueue = messageQueue;

        // 显式配置实体类 -> Repository 的映射
        this.repositoryMap = new HashMap<>();
        this.repositoryMap.put(AisMessage.class, aisMessageRepository);

        logger.info("Repository Map Initialized: {}", repositoryMap.keySet());
    }

    // 定时将对列中消息存入数据库中
    @Scheduled(fixedRateString = "${database.batch.interval:3000}", initialDelay = 5000)
    public void processQueue() {
        int queueSize = messageQueue.getSize();
        if (queueSize == 0) {
            return;
        }

        logger.info("Starting batch processing. Queue size: {}", queueSize);

        List<AisMessage> batch = new ArrayList<>(batchSize);
        messageQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) {
            return;
        }

        // 核心逻辑：按实体类型对批次中的数据进行分组
        Map<Class<?>, List<AisMessage>> groupedByClass = batch.stream()
                .collect(Collectors.groupingBy(AisMessage::getClass));

        // 遍历分组，为每种类型的实体调用对应的Repository进行批量保存
        groupedByClass.forEach((entityClass, entities) -> {
            JpaRepository repository = repositoryMap.get(entityClass);
            if (repository != null) {
                try {
                    logger.info("Saving {} entities of type {}", entities.size(), entityClass.getSimpleName());
                    repository.saveAll(entities);
                } catch (Exception e) {
                    logger.error("Error batch saving entities of type {}", entityClass.getSimpleName(), e);
                    // 这里可以加入错误处理逻辑，比如将失败的数据存入死信队列
                }
            } else {
                logger.warn("No repository found for entity class: {}", entityClass.getSimpleName());
            }
        });
    }
}
