package com.echomind.saas.ticket.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TicketActionRequest(
        @NotNull @PositiveOrZero Long expectedVersion
) {
    public record Resolve(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 20_000) String resolution
    ) {
    }

    public record Reopen(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 10_000) String reason
    ) {
    }
}
