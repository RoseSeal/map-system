package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.risk.config.ShipDomainProperties;
import com.whut.map.map_service.risk.engine.safety.ShipDomainEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.whut.map.map_service.shared.domain.ShipStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class GetOwnShipStateToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ShipDomainProperties properties = new ShipDomainProperties();
    private final ShipDomainEngine engine = new ShipDomainEngine(properties);
    private final GetOwnShipStateTool tool = new GetOwnShipStateTool(MAPPER, engine);

    private ToolCall noArgCall() {
        return new ToolCall("cid-1", "get_own_ship_state", MAPPER.createObjectNode());
    }

    private AgentSnapshot snapshotWithOwnShip(double sog) {
        LlmRiskOwnShipContext ownShip = LlmRiskOwnShipContext.builder()
                .id("own-ship").longitude(114.35).latitude(30.54)
                .sog(sog).cog(83.1).heading(82.0).confidence(0.99).build();
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip).targets(List.of()).build();
        return new AgentSnapshot(42L, ctx, Map.of());
    }

    @Test
    void nullRiskContextReturnsSnapshotIncomplete() {
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, null);

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("SNAPSHOT_INCOMPLETE");
    }

    @Test
    void nullOwnShipReturnsSnapshotIncomplete() {
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(null).targets(List.of()).build();
        AgentSnapshot snapshot = new AgentSnapshot(1L, ctx, Map.of());

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("SNAPSHOT_INCOMPLETE");
    }

    @Test
    void ownShipFieldsAreCopiedCorrectly() {
        AgentSnapshot snapshot = snapshotWithOwnShip(10.4);

        ToolResult result = tool.execute(noArgCall(), snapshot);

        ObjectNode ownShipNode = (ObjectNode) result.payload().get("own_ship");
        assertThat(ownShipNode.get("id").asText()).isEqualTo("own-ship");
        assertThat(ownShipNode.get("longitude").asDouble()).isEqualTo(114.35);
        assertThat(ownShipNode.get("latitude").asDouble()).isEqualTo(30.54);
        assertThat(ownShipNode.get("sog_kn").asDouble()).isEqualTo(10.4);
        assertThat(ownShipNode.get("cog_deg").asDouble()).isEqualTo(83.1);
        assertThat(ownShipNode.get("heading_deg").asDouble()).isEqualTo(82.0);
        assertThat(ownShipNode.get("confidence").asDouble()).isEqualTo(0.99);
    }

    @Test
    void shipStatusRebuiltWithNullRoleAndMsgTimeAndEmptyQualityFlags() {
        ShipDomainEngine spyEngine = spy(engine);
        GetOwnShipStateTool spyTool = new GetOwnShipStateTool(MAPPER, spyEngine);
        ArgumentCaptor<ShipStatus> captor = ArgumentCaptor.forClass(ShipStatus.class);

        spyTool.execute(noArgCall(), snapshotWithOwnShip(8.0));

        verify(spyEngine).consume(captor.capture());
        ShipStatus captured = captor.getValue();
        assertThat(captured.getRole()).isNull();
        assertThat(captured.getMsgTime()).isNull();
        assertThat(captured.getQualityFlags()).isEqualTo(Set.of());
    }

    @Test
    void safetyDomainMatchesEngineAtReferenceSpeed() {
        // sog = referenceSpeedKn (8.0) → speedFactor = 1.0 → baseline values
        AgentSnapshot snapshot = snapshotWithOwnShip(8.0);

        ToolResult result = tool.execute(noArgCall(), snapshot);

        ObjectNode domain = (ObjectNode) result.payload().get("safety_domain");
        assertThat(domain.get("shape_type").asText()).isEqualTo("ellipse");
        assertThat(domain.get("fore_nm").asDouble()).isCloseTo(0.2, within(1e-9));
        assertThat(domain.get("aft_nm").asDouble()).isCloseTo(0.04, within(1e-9));
        assertThat(domain.get("port_nm").asDouble()).isCloseTo(0.08, within(1e-9));
        assertThat(domain.get("stbd_nm").asDouble()).isCloseTo(0.08, within(1e-9));
    }

    @Test
    void safetyDomainScalesWithSpeed() {
        // sog = 16.0 kn, referenceSpeedKn = 8.0 → speedFactor = 2.0 (max) → doubled
        AgentSnapshot snapshot = snapshotWithOwnShip(16.0);

        ToolResult result = tool.execute(noArgCall(), snapshot);

        ObjectNode domain = (ObjectNode) result.payload().get("safety_domain");
        assertThat(domain.get("fore_nm").asDouble()).isCloseTo(0.4, within(1e-9));
        assertThat(domain.get("aft_nm").asDouble()).isCloseTo(0.08, within(1e-9));
    }

    @Test
    void customPropertiesAreHonouredByEngine() {
        ShipDomainProperties custom = new ShipDomainProperties();
        custom.setBaseForeNm(0.5);
        custom.setBaseAftNm(0.1);
        custom.setBasePortNm(0.2);
        custom.setBaseStbdNm(0.2);
        custom.setReferenceSpeedKn(10.0);
        ShipDomainEngine customEngine = new ShipDomainEngine(custom);
        GetOwnShipStateTool customTool = new GetOwnShipStateTool(MAPPER, customEngine);

        AgentSnapshot snapshot = snapshotWithOwnShip(10.0);
        ToolResult result = customTool.execute(noArgCall(), snapshot);

        ObjectNode domain = (ObjectNode) result.payload().get("safety_domain");
        assertThat(domain.get("fore_nm").asDouble()).isCloseTo(0.5, within(1e-9));
        assertThat(domain.get("aft_nm").asDouble()).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void snapshotVersionIsPresent() {
        ToolResult result = tool.execute(noArgCall(), snapshotWithOwnShip(8.0));

        assertThat(result.payload().get("snapshot_version").asLong()).isEqualTo(42L);
    }

    @Test
    void doesNotAccessLiveStoreOnlyUsesSnapshot() {
        // Verifying the tool only calls consume() on the injected engine — no external calls
        ShipDomainEngine spyEngine = spy(engine);
        GetOwnShipStateTool spyTool = new GetOwnShipStateTool(MAPPER, spyEngine);

        spyTool.execute(noArgCall(), snapshotWithOwnShip(8.0));

        verify(spyEngine).consume(any(ShipStatus.class));
    }
}
