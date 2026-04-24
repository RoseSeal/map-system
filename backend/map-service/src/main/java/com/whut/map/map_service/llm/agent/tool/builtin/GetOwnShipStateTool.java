package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.risk.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class GetOwnShipStateTool implements AgentTool {

    private final ObjectMapper mapper;
    private final ShipDomainEngine shipDomainEngine;
    private final ToolDefinition definition;

    public GetOwnShipStateTool(ObjectMapper mapper, ShipDomainEngine shipDomainEngine) {
        this.mapper = mapper;
        this.shipDomainEngine = shipDomainEngine;
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties");
        this.definition = new ToolDefinition(
                AgentToolNames.GET_OWN_SHIP_STATE,
                "Returns the current own-ship position, kinematics, and safety domain dimensions computed from the frozen snapshot.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getOwnShip() == null) {
            return errorResult(call, "SNAPSHOT_INCOMPLETE", "Own-ship data is not available in this snapshot");
        }

        LlmRiskOwnShipContext ownShipCtx = snapshot.riskContext().getOwnShip();

        ShipStatus shipStatus = ShipStatus.builder()
                .id(ownShipCtx.getId())
                .role(null)
                .longitude(ownShipCtx.getLongitude())
                .latitude(ownShipCtx.getLatitude())
                .sog(ownShipCtx.getSog())
                .cog(ownShipCtx.getCog())
                .heading(ownShipCtx.getHeading())
                .confidence(ownShipCtx.getConfidence())
                .msgTime(null)
                .qualityFlags(Set.of())
                .build();

        ShipDomainResult domain = shipDomainEngine.consume(shipStatus);

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());

        ObjectNode ownShipNode = payload.putObject("own_ship")
                .put("id", ownShipCtx.getId())
                .put("longitude", ownShipCtx.getLongitude())
                .put("latitude", ownShipCtx.getLatitude())
                .put("sog_kn", ownShipCtx.getSog())
                .put("cog_deg", ownShipCtx.getCog());
        if (ownShipCtx.getHeading() != null) ownShipNode.put("heading_deg", ownShipCtx.getHeading());
        else ownShipNode.putNull("heading_deg");
        if (ownShipCtx.getConfidence() != null) ownShipNode.put("confidence", ownShipCtx.getConfidence());
        else ownShipNode.putNull("confidence");

        payload.putObject("safety_domain")
                .put("shape_type", domain.getShapeType())
                .put("fore_nm", domain.getForeNm())
                .put("aft_nm", domain.getAftNm())
                .put("port_nm", domain.getPortNm())
                .put("stbd_nm", domain.getStbdNm());

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private ToolResult errorResult(ToolCall call, String errorCode, String message) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "ERROR")
                .put("error_code", errorCode)
                .put("message", message);
        return new ToolResult(call.callId(), call.toolName(), payload);
    }
}
