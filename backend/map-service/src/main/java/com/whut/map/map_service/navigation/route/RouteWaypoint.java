package com.whut.map.map_service.navigation.route;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RouteWaypoint(
        @JsonProperty("seq") int seq,
        @JsonProperty("lon") double lon,
        @JsonProperty("lat") double lat,
        @JsonProperty("turn_radius_m") double turnRadiusM
) {
}
