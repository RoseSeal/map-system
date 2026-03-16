package com.whut.map.map_service.engine.trajectoryprediction;

import com.whut.map.map_service.domain.ShipStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CvPredictionEngine {

    private CvPredictionResult predict() {
        // 1. 获取历史数据
        // 2. 数据预处理
        // 3. 模型预测
        // 4. 将结果发送到WebSocket服务
        return null;
    }

    public CvPredictionResult consume(ShipStatus message) {
        // 1. 从数据源获取数据
        log.debug("Received AIS message for CV prediction, MMSI: {}", message.getId());
        // 2. 调用预测方法
        return predict();
    }
}
