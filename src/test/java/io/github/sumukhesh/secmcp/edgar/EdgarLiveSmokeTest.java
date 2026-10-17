package io.github.sumukhesh.secmcp.edgar;

import java.time.Duration;
import java.util.Optional;

import io.github.sumukhesh.secmcp.config.EdgarProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real SEC EDGAR API (5 requests). Opt-in so normal builds never touch the network:
 * <pre>./mvnw test -Dtest=EdgarLiveSmokeTest -Dedgar.live=true</pre>
 * Uses edgar.user-agent from config/application.yml or EDGAR_USER_AGENT.
 */
@EnabledIfSystemProperty(named = "edgar.live", matches = "true")
@SpringBootTest(properties = "spring.ai.mcp.server.stdio=false")
class EdgarLiveSmokeTest {

    private static final Cik APPLE = new Cik(320193);

    @Autowired
    EdgarClient client;

    @Autowired
    TickerCikResolver resolver;

    @Autowired
    EdgarProperties props;

    @Test
    void resolvesTickerAndFetchesEveryEndpointType() {
        Optional<CompanyRef> apple = resolver.findByTicker("aapl").block(Duration.ofSeconds(30));
        assertThat(apple).map(CompanyRef::cik).contains(APPLE);
        assertThat(resolver.findByTicker("BRK.B").block()).map(CompanyRef::cik).contains(new Cik(1067983));

        SubmissionsResponse submissions = client.fetchSubmissions(APPLE).block(Duration.ofSeconds(30));
        assertThat(submissions.name()).isEqualTo("Apple Inc.");
        SubmissionsResponse.Recent recent = submissions.filings().recent();
        assertThat(recent.form()).hasSameSizeAs(recent.accessionNumber()).hasSameSizeAs(recent.primaryDocument());
        int tenK = recent.form().indexOf("10-K");
        assertThat(tenK).isNotNegative();

        JsonNode facts = client.fetchCompanyFacts(APPLE).block(Duration.ofSeconds(30));
        assertThat(facts.path("facts").path("us-gaap").size()).isGreaterThan(100);

        String html = client.fetchFilingDocument(APPLE, AccessionNumber.parse(recent.accessionNumber().get(tenK)),
                recent.primaryDocument().get(tenK)).block(Duration.ofSeconds(60));
        assertThat(html).containsIgnoringCase("Risk Factors").hasSizeGreaterThan(100_000);
    }
}
