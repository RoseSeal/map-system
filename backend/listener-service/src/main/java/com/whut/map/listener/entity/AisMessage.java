package com.whut.map.listener.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "ais_history")
public class AisMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private OffsetDateTime msgTime;

    private String mmsi;

    // 空间信息
    private Point geom;

    // 运动信息 (单位标准化：节, 度)
    private Double sog; // Knots

    private Double cog; // Degrees

    // 扩展信息
    private Double heading;
}