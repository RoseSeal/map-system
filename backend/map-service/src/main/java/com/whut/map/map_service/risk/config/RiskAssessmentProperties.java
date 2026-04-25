package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "risk.assessment")
public class RiskAssessmentProperties {

    private double alarmDcpaNm = 0.15;
    private double warningDcpaNm = 0.25;
    private double cautionDcpaNm = 0.5;

    private double alarmTcpaSec = 150.0;
    private double warningTcpaSec = 450.0;
    private double cautionTcpaSec = 900.0;
}
