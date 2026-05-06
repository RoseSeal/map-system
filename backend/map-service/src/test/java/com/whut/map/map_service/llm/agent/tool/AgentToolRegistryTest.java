package com.whut.map.map_service.llm.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTool stubTool(String name) {
        ObjectNode schema = MAPPER.createObjectNode();
        ToolDefinition def = new ToolDefinition(name, "description for " + name, schema);
        return new AgentTool() {
            @Override public ToolDefinition getDefinition() { return def; }
            @Override public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
                return new ToolResult(call.callId(), call.toolName(),
                        MAPPER.createObjectNode().put("status", "OK"));
            }
        };
    }

    @Test
    void getToolDefinitionsReturnsSortedByName() {
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(stubTool("z_tool"), stubTool("a_tool"), stubTool("m_tool")), MAPPER);

        List<ToolDefinition> defs = registry.getToolDefinitions();

        assertThat(defs).extracting(ToolDefinition::name)
                .containsExactly("a_tool", "m_tool", "z_tool");
    }

    @Test
    void getToolDefinitionsReturnsCachedReference() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(stubTool("tool_a")), MAPPER);

        assertThat(registry.getToolDefinitions()).isSameAs(registry.getToolDefinitions());
    }

    @Test
    void unknownToolNameReturnsUnknownToolErrorPayload() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(), MAPPER);
        ToolCall call = new ToolCall("id-1", "nonexistent", MAPPER.createObjectNode());
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, null);

        ToolResult result = registry.execute(call, snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("UNKNOWN_TOOL");
        assertThat(result.payload().get("message").asText()).contains("nonexistent");
    }

    @Test
    void unknownToolResultPreservesCallIdAndToolName() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(), MAPPER);
        ToolCall call = new ToolCall("call-99", "no_such_tool", MAPPER.createObjectNode());

        ToolResult result = registry.execute(call, new AgentSnapshot(1L, null, null));

        assertThat(result.callId()).isEqualTo("call-99");
        assertThat(result.toolName()).isEqualTo("no_such_tool");
    }

    @Test
    void duplicateToolNameFailsAtConstruction() {
        assertThatThrownBy(() -> new AgentToolRegistry(
                List.of(stubTool("same"), stubTool("same")), MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same");
    }

    @Test
    void knownToolIsDispatched() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(stubTool("my_tool")), MAPPER);
        ToolCall call = new ToolCall("id-2", "my_tool", MAPPER.createObjectNode());

        ToolResult result = registry.execute(call, new AgentSnapshot(1L, null, null));

        assertThat(result.payload().get("status").asText()).isEqualTo("OK");
    }

    @Test
    void hydrologyToolsCanBeListedByRegistry() {
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(
                        stubTool(AgentToolNames.QUERY_BATHYMETRY),
                        stubTool(AgentToolNames.EVALUATE_MANEUVER_HYDROLOGY)
                ),
                MAPPER
        );

        assertThat(registry.getToolDefinitions())
                .extracting(ToolDefinition::name)
                .containsExactly(
                        AgentToolNames.EVALUATE_MANEUVER_HYDROLOGY,
                        AgentToolNames.QUERY_BATHYMETRY
                );
    }
}
