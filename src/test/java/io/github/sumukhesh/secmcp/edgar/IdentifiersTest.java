package io.github.sumukhesh.secmcp.edgar;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {"320193", "0000320193", "CIK0000320193", " cik320193 "})
    void parsesCikVariants(String raw) {
        Cik cik = Cik.parse(raw);

        assertThat(cik.value()).isEqualTo(320193L);
        assertThat(cik.padded()).isEqualTo("0000320193");
        assertThat(cik.toString()).isEqualTo("320193");
    }

    @Test
    void padsTenDigitCikUnchanged() {
        assertThat(Cik.parse("1234567890").padded()).isEqualTo("1234567890");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "12345678901", "320193/../x", "-5"})
    void rejectsInvalidCiks(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cik.parse(raw));
    }

    @Test
    void parsesDashedAndUndashedAccessionNumbers() {
        assertThat(AccessionNumber.parse("0000320193-24-000123").withoutDashes()).isEqualTo("000032019324000123");
        assertThat(AccessionNumber.parse("000032019324000123").value()).isEqualTo("0000320193-24-000123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0000320193-24-00012", "../../etc/passwd", "0000320193-24-000123/x"})
    void rejectsInvalidAccessionNumbers(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccessionNumber.parse(raw));
    }
}
