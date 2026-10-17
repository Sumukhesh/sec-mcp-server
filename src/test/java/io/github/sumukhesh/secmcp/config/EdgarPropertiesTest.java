package io.github.sumukhesh.secmcp.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EdgarPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<ConstraintViolation<EdgarProperties>> validate(String userAgent) {
        URI base = URI.create("https://www.sec.gov");
        return VALIDATOR.validate(new EdgarProperties(userAgent, base, base, 8, Duration.ofSeconds(30), 1L,
                new EdgarProperties.Retry(4, Duration.ofMillis(500), Duration.ofSeconds(8)), Duration.ofHours(24)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Jane Doe jane@example.com", "Acme Research ops@acme.io"})
    void acceptsNameAndEmail(String userAgent) {
        assertThat(validate(userAgent)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"REPLACE_ME your@email.com", "jane@example.com", "Jane Doe"})
    void rejectsMissingOrPlaceholderUserAgent(String userAgent) {
        assertThat(validate(userAgent)).isNotEmpty()
                .allSatisfy(v -> assertThat(v.getMessage()).contains("edgar.user-agent"));
    }
}
