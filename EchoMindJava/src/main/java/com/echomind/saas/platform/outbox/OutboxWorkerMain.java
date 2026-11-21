package com.echomind.saas.platform.outbox;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Standalone process: no HTTP, chat models, or external event destination. */
public final class OutboxWorkerMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxWorkerMain.class);
    private static final String CONSUMER_NAME = "deskverify-ticket-audit";

    private OutboxWorkerMain() {
    }

    public static void run() {
        Path marker = Path.of(System.getProperty("java.io.tmpdir"), "deskverify-outbox.ready");
        try {
            Files.deleteIfExists(marker);
            Duration lease = Duration.parse(setting("ECHOMIND_OUTBOX_LEASE_DURATION", "PT15S"));
            int pollDelay = Integer.parseInt(setting("ECHOMIND_OUTBOX_POLL_DELAY_MS", "1000"));
            if (lease.compareTo(Duration.ofSeconds(1)) < 0
                    || lease.compareTo(Duration.ofMinutes(5)) > 0
                    || pollDelay < 50 || pollDelay > 30_000) {
                throw new IllegalArgumentException("invalid worker timing settings");
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(setting("ECHOMIND_DB_URL", ""));
            config.setUsername(setting("ECHOMIND_DB_USERNAME", ""));
            config.setPassword(setting("ECHOMIND_DB_PASSWORD", ""));
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setInitializationFailTimeout(-1);
            config.setConnectionTimeout(2_000);
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                jdbc.setQueryTimeout(5);
                poll(jdbc, marker, UUID.randomUUID().toString(), lease, pollDelay);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | IOException exception) {
            LOGGER.error("Outbox worker could not run ({})", exception.getClass().getSimpleName());
            throw new IllegalStateException("outbox worker is unavailable");
        }
    }

    private static void poll(JdbcTemplate jdbc, Path marker, String workerId, Duration lease, int pollDelay)
            throws IOException, InterruptedException {
        JdbcOutboxRepository repository = new JdbcOutboxRepository(jdbc);
        boolean schemaReady = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!schemaReady) {
                    // Only the backend owns migrations. Do not consume pending data with a pre-V5 Inbox key.
                    schemaReady = Integer.valueOf(1).equals(jdbc.queryForObject("""
                            select count(*) from flyway_schema_history
                            where version = '5' and success = true
                            """, Integer.class));
                }
                if (schemaReady) {
                    var claims = repository.claimWithLease(workerId, lease);
                    if (!claims.isEmpty()) {
                        deliver(repository, claims.getFirst(), workerId);
                    }
                    Files.writeString(marker, "");
                }
            } catch (DataAccessException exception) {
                if (schemaReady) {
                    LOGGER.warn("Outbox poll failed ({})", exception.getClass().getSimpleName());
                }
            }
            TimeUnit.MILLISECONDS.sleep(pollDelay);
        }
    }

    private static void deliver(JdbcOutboxRepository repository, OutboxClaim claim, String workerId) {
        try {
            repository.acknowledgeLocalDelivery(claim, workerId, CONSUMER_NAME);
            LOGGER.info("Published local outbox event id={} worker={}", claim.message().id(), workerId);
        } catch (OutboxStateException exception) {
            LOGGER.info("Outbox claim is no longer current id={} worker={}", claim.message().id(), workerId);
        } catch (DataAccessException exception) {
            try {
                repository.releaseForRetry(claim.message().id(), workerId, claim.claimToken(),
                        Instant.now().plusSeconds(5), "local inbox delivery unavailable");
            } catch (OutboxStateException ignored) {
                // The next worker owns retry after lease expiry.
            }
            throw exception;
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
