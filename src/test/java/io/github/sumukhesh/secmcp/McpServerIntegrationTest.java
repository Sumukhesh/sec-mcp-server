package io.github.sumukhesh.secmcp;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real server over Streamable HTTP and talks to it with the official MCP Java client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "edgar.user-agent=Integration Test test@example.com")
@ActiveProfiles("http")
class McpServerIntegrationTest {

    @LocalServerPort
    int port;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build()).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
    }

    @Test
    void listsPingTool() {
        assertThat(client.listTools().tools())
                .extracting(Tool::name)
                .contains("ping");
    }

    @Test
    void callsPingTool() {
        CallToolResult result = client.callTool(new CallToolRequest("ping", Map.of("message", "hi")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent())
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("status", "ok")
                .containsEntry("echo", "hi");
    }
}
