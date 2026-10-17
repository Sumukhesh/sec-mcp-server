package io.github.sumukhesh.secmcp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real server over Streamable HTTP, stubs SEC EDGAR with saved real responses, and
 * talks to the server with the official MCP Java client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "edgar.user-agent=Integration Test test@example.com")
@ActiveProfiles("http")
class McpServerIntegrationTest {

    @RegisterExtension
    static WireMockExtension sec = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void pointAtStubbedSec(DynamicPropertyRegistry registry) {
        registry.add("edgar.www-base-url", sec::baseUrl);
        registry.add("edgar.data-base-url", sec::baseUrl);
    }

    @BeforeEach // WireMockExtension resets stubs before every test
    void stubSec() throws Exception {
        sec.stubFor(get("/files/company_tickers.json").willReturn(okJson(fixture("company_tickers.json"))));
        sec.stubFor(get("/submissions/CIK0000320193.json")
                .willReturn(okJson(fixture("submissions-CIK0000320193.json"))));
        sec.stubFor(get("/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm")
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(filing("aapl-20250927-10k.htm.gz"))));
    }

    private static byte[] filing(String name) throws Exception {
        try (InputStream in = new GZIPInputStream(
                McpServerIntegrationTest.class.getResourceAsStream("/fixtures/filings/" + name))) {
            return in.readAllBytes();
        }
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = McpServerIntegrationTest.class.getResourceAsStream("/fixtures/edgar/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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
    void listsToolsWithSnakeCaseOutputSchema() {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).contains("ping", "search_company", "get_filing_section");
        Tool search = tools.stream().filter(t -> t.name().equals("search_company")).findFirst().orElseThrow();
        assertThat(search.inputSchema()).extractingByKey("required")
                .asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly("query");
        assertThat(search.outputSchema()).extractingByKey("properties")
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsOnlyKeys("company", "recent_filings", "other_matches");
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

    @Test
    void searchCompanyReturnsCompanyAndFilings() {
        CallToolResult result = client.callTool(new CallToolRequest("search_company", Map.of("query", "Apple")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        Map<String, Object> content = asMap(result.structuredContent());
        assertThat(asMap(content.get("company")))
                .containsEntry("cik", "0000320193")
                .containsEntry("name", "Apple Inc.")
                .containsEntry("ticker", "AAPL");
        assertThat((List<?>) content.get("recent_filings")).hasSize(10)
                .allSatisfy(f -> assertThat(asMap(f)).containsKeys("form", "filing_date", "accession_number"));
    }

    @Test
    void getFilingSectionSchemaListsSectionEnumAndSnakeCaseArguments() {
        Tool tool = client.listTools().tools().stream()
                .filter(t -> t.name().equals("get_filing_section")).findFirst().orElseThrow();

        Map<String, Object> properties = asMap(tool.inputSchema().get("properties"));
        assertThat(properties).containsKeys("section", "accession_number", "ticker", "form", "fiscal_year",
                "offset", "limit");
        assertThat(asMap(properties.get("section"))).extractingByKey("enum")
                .asInstanceOf(InstanceOfAssertFactories.LIST).containsExactlyInAnyOrder("RISK_FACTORS", "MDA");
        assertThat(tool.inputSchema()).extractingByKey("required")
                .asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly("section");
    }

    @Test
    void searchThenReadSectionEndToEnd() {
        CallToolResult search = client.callTool(new CallToolRequest("search_company",
                Map.of("query", "Apple", "form", "10-K")));
        Map<String, Object> latest = asMap(((List<?>) asMap(search.structuredContent()).get("recent_filings")).getFirst());

        CallToolResult section = client.callTool(new CallToolRequest("get_filing_section", Map.of(
                "accession_number", latest.get("accession_number"), "ticker", "AAPL",
                "section", "RISK_FACTORS", "limit", 2000)));

        assertThat(section.isError()).isNotEqualTo(Boolean.TRUE);
        Map<String, Object> page = asMap(section.structuredContent());
        assertThat((String) page.get("text")).startsWith("Item 1A. Risk Factors");
        assertThat(page).containsEntry("offset", 0).containsEntry("returned_chars", 2000)
                .containsEntry("has_more", true);
        assertThat((Integer) page.get("total_chars")).isGreaterThan(50_000);
        assertThat(asMap(page.get("filing"))).containsEntry("accession_number", "0000320193-25-000079")
                .containsEntry("period_of_report", "2025-09-27");
    }

    @Test
    void getFilingSectionReportsBadInputAsToolError() {
        CallToolResult result = client.callTool(new CallToolRequest("get_filing_section",
                Map.of("section", "MDA", "ticker", "AAPL", "form", "8-K")));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).contains("8-K filings have no Risk Factors");
    }

    @Test
    void searchCompanyReportsUnknownCompanyAsToolError() {
        CallToolResult result = client.callTool(new CallToolRequest("search_company", Map.of("query", "QWXZQ")));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .contains("No company found for 'QWXZQ'. Try the ticker symbol");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
