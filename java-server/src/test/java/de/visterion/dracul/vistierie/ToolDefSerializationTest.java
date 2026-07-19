package de.visterion.dracul.vistierie;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDefSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void httpToolLeavesMcpFieldsNull() {
        var tool = new ToolDef(
                "get_positions", "desc", null, null,
                "target-agent", "http://x/webhook", 30);

        assertThat(tool.type()).isNull();
        assertThat(tool.mcp_server_url()).isNull();
        assertThat(tool.mcp_tool_name()).isNull();
        assertThat(tool.mcp_timeout_seconds()).isNull();

        String json = mapper.writeValueAsString(tool);
        assertThat(json).contains("\"mcp_server_url\":null");
        assertThat(json).contains("\"mcp_tool_name\":null");
        assertThat(json).contains("\"mcp_timeout_seconds\":null");
    }

    @Test
    void mcpToolCarriesMcpFieldsAndNullsHttpFields() {
        var tool = new ToolDef(
                "get_positions", "desc", null, "mcp",
                null, null, null,
                "http://agora/mcp", "get_positions", 30);

        assertThat(tool.type()).isEqualTo("mcp");
        assertThat(tool.mcp_server_url()).isEqualTo("http://agora/mcp");
        assertThat(tool.mcp_tool_name()).isEqualTo("get_positions");
        assertThat(tool.mcp_timeout_seconds()).isEqualTo(30);
        assertThat(tool.target_agent()).isNull();
        assertThat(tool.webhook_url()).isNull();
    }
}
