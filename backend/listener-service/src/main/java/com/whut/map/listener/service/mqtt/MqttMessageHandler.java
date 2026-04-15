package com.whut.map.listener.service.mqtt;

import com.whut.map.listener.entity.AisMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.listener.service.queue.MessageQueue;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class MqttMessageHandler implements MessageHandler {

    // 消息队列
    @Autowired
    private final MessageQueue messageQueue;

    private static final Logger logger = LoggerFactory.getLogger(MqttMessageHandler.class);

    // 负责数据解析
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 负责领域对象创建
    private final GeometryFactory geometryFactory = new GeometryFactory();

    // 处理MQTT消息
    @Override
    public void handleMessage(Message<?> message) {
        try {
            // 接收数据
            String payload = (String) message.getPayload();
            String topic = (String) message.getHeaders().get("mqtt_receivedTopic");

            try {
                JsonNode data = objectMapper.readTree(payload);

                AisMessage entity = convertToEntity(topic, data);
                if (entity != null) {
                    messageQueue.enqueue(entity);
                    logger.debug("Enqueued {} from topic {}", entity.getClass().getSimpleName(), topic);
                } else {
                    logger.warn("No entity mapped for topic {}", topic);
                }

            } catch (Exception e) {
                logger.error("Error handling MQTT message from topic {}: {}", topic, e.getMessage(), e);
            }

        } catch (Exception e) {
            logger.error("消息处理失败: {}", e.getMessage(), e);
        }
    }

    // MQTT消息分发函数
    private AisMessage convertToEntity(String topic, JsonNode data) {
        if (topic == null) {
            return null;
        }

        switch (topic.trim()) {
            case "usv/AisMessage":
                return buildAisMessageEntity(data);
            default:
                logger.debug("未处理的 topic: {}", topic);
                return null;
        }
    }

    private OffsetDateTime parseMsgTime(JsonNode data) {
        JsonNode tsNode = data.get("timestamp");
        if (tsNode != null && tsNode.isNumber()) {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(tsNode.asLong()), ZoneOffset.UTC);
        }

        String raw = textOf(data, "MSGTIME", "msgTime");
        if (raw == null || raw.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }

        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception ignored) {
            // 兼容常见CSV时间格式：yyyy-MM-dd HH:mm:ss
            LocalDateTime local = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss"));
            return local.atOffset(ZoneOffset.UTC);
        }
    }

    private String textOf(JsonNode data, String... keys) {
        for (String key : keys) {
            JsonNode node = data.get(key);
            if (node != null && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }

    private double doubleOf(JsonNode data, String... keys) {
        String value = textOf(data, keys);
        return value == null || value.isBlank() ? 0.0 : Double.parseDouble(value);
    }

    // 各MQTT消息的数据处理函数：
    private AisMessage buildAisMessageEntity(JsonNode data) {
        try {
            AisMessage entity = new AisMessage();

            entity.setMsgTime(parseMsgTime(data));
            entity.setMmsi(textOf(data, "mmsi", "MMSI"));

            double lat = doubleOf(data, "lat", "LAT");
            double lon = doubleOf(data, "lon", "LON");
            Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
            point.setSRID(4326);
            entity.setGeom(point);

            entity.setSog(doubleOf(data, "sog", "SOG"));
            entity.setCog(doubleOf(data, "cog", "COG"));
            entity.setHeading(doubleOf(data, "heading", "HEADING"));

            logger.debug("AisMessage 消息处理成功, msgTime={}", entity.getMsgTime());
            return entity;
        } catch (Exception e) {
            logger.error("AisMessage 消息处理失败: {}", e.getMessage(), e);
            return null;
        }
    }
}