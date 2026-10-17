package io.github.sumukhesh.secmcp.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.github.sumukhesh.secmcp.model.PingResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class PingToolTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private final PingTool tool = new PingTool("1.2.3", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void echoesMessageWithStatusVersionAndTime() {
        StepVerifier.create(tool.ping("  hello  "))
                .expectNext(new PingResult("ok", "1.2.3", "hello", NOW))
                .verifyComplete();
    }

    @Test
    void nullMessageEchoesEmptyString() {
        StepVerifier.create(tool.ping(null))
                .assertNext(r -> assertThat(r.echo()).isEmpty())
                .verifyComplete();
    }

    @Test
    void longMessageIsTruncated() {
        String longMessage = "x".repeat(PingTool.MAX_ECHO_LENGTH + 50);

        StepVerifier.create(tool.ping(longMessage))
                .assertNext(r -> assertThat(r.echo()).hasSize(PingTool.MAX_ECHO_LENGTH))
                .verifyComplete();
    }
}
