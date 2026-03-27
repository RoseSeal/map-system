package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class FrontendMessage {

    private String type;
    private JsonNode message;

}
