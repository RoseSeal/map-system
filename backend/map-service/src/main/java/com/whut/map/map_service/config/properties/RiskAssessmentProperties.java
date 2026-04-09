package com.whut.map.map_service.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "risk.assessment")
public class RiskAssessmentProperties {

    private double alarmDcpaNm = 0.3;
    private double warningDcpaNm = 0.5;
    private double cautionDcpaNm = 1.0;

    private double alarmTcpaSec = 300.0;
    private double warningTcpaSec = 900.0;
    private double cautionTcpaSec = 1800.0;
}
