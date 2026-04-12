package com.whut.map.map_service.event;

import com.whut.map.map_service.dto.RiskObjectDto;

import java.util.function.LongConsumer;

public record RiskFrame(
        RiskObjectDto riskObject,
        LongConsumer beforePublish
) {
}
