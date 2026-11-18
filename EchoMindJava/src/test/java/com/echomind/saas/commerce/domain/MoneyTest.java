package com.echomind.saas.commerce.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void acceptsNonNegativeAmountsInUppercaseIsoCurrencies() {
        assertThat(new Money(0, "CNY")).isEqualTo(new Money(0, "CNY"));
        assertThat(new Money(12_345, "USD").amountMinor()).isEqualTo(12_345);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> new Money(-1, "CNY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void rejectsMissingOrMalformedCurrencies() {
        assertThatThrownBy(() -> new Money(1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, "cny"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, "CN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, "C1Y"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
