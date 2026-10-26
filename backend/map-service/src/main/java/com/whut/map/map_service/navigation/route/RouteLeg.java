package com.whut.map.map_service.navigation.route;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RouteLeg(
        @JsonProperty("from_seq") int fromSeq,
        @JsonProperty("to_seq") int toSeq,
        @JsonProperty("speed_kn") double speedKn,
        @JsonProperty("xtd_port_m") double xtdPortM,
        @JsonProperty("xtd_starboard_m") double xtdStarboardM
) {
}
