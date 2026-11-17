package com.echomind.saas.api.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdSanitizerTest {

    @Test
    void returnsUnavailableForMissingOrBlankValues() {
        assertThat(RequestIdSanitizer.sanitize(null)).isEqualTo("unavailable");
        assertThat(RequestIdSanitizer.sanitize("   ")).isEqualTo("unavailable");
    }

    @Test
    void preservesSafeValuesUpTo128Characters() {
        String safe = "A-z_0.9:request-42";

        assertThat(RequestIdSanitizer.sanitize(safe)).isEqualTo(safe);
        assertThat(RequestIdSanitizer.sanitize("a".repeat(128))).isEqualTo("a".repeat(128));
    }

    @Test
    void rejectsLongOrUnsafeValues() {
        assertThat(RequestIdSanitizer.sanitize("a".repeat(129))).isEqualTo("unavailable");
        assertThat(RequestIdSanitizer.sanitize("request\r\nforged-log-line"))
                .isEqualTo("unavailable");
        assertThat(RequestIdSanitizer.sanitize("request with spaces")).isEqualTo("unavailable");
    }
}
