package com.whut.map.map_service.risk.event;

import com.whut.map.map_service.shared.dto.RiskObjectDto;

import java.util.function.LongConsumer;

public record RiskFrame(
        RiskObjectDto riskObject,
        LongConsumer beforePublish
) {
}
