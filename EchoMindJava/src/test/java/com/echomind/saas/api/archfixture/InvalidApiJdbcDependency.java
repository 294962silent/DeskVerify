package com.echomind.saas.api.archfixture;

import org.springframework.jdbc.core.JdbcTemplate;

/** Deliberately invalid API-layer fixture used only by the isolated ArchUnit guard. */
public class InvalidApiJdbcDependency {

    private final JdbcTemplate jdbc;

    public InvalidApiJdbcDependency(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
}
