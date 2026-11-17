package com.echomind.saas.testsupport;

import org.testcontainers.containers.MySQLContainer;

public final class MySqlTestContainers {

    private MySqlTestContainers() {
    }

    public static MySQLContainer<?> create() {
        return new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("echomind")
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true");
    }
}
