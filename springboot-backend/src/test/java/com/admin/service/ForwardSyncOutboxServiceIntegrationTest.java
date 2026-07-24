package com.admin.service;

import com.admin.service.ForwardSyncOutboxService.SyncTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardSyncOutboxServiceIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private ForwardSyncOutboxService outboxService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:forward-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                        + "DATABASE_TO_LOWER=TRUE",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE `forward` (
                    id BIGINT PRIMARY KEY,
                    sync_status VARCHAR(16),
                    sync_error VARCHAR(2000),
                    delete_requested TINYINT NOT NULL,
                    updated_time BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE forward_sync_outbox (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    forward_id BIGINT NOT NULL,
                    operation VARCHAR(16) NOT NULL,
                    old_tunnel_id BIGINT,
                    old_service_name VARCHAR(160),
                    status VARCHAR(16) NOT NULL,
                    attempts INT NOT NULL,
                    next_attempt_at BIGINT NOT NULL,
                    locked_at BIGINT,
                    last_error VARCHAR(2000),
                    created_time BIGINT NOT NULL,
                    updated_time BIGINT NOT NULL
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO `forward`
                    (id, sync_status, sync_error, delete_requested, updated_time)
                VALUES (1, 'SYNCED', NULL, 0, 0)
                """);
        outboxService = new ForwardSyncOutboxService(jdbcTemplate);
    }

    @Test
    void claimsAndCompletesQueuedDesiredState() {
        outboxService.enqueueUpsert(1L, null, null);

        List<SyncTask> tasks = outboxService.findDueTasks(10);
        assertEquals(1, tasks.size());
        assertTrue(outboxService.claim(tasks.get(0).getId()));

        outboxService.complete(tasks.get(0));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM forward_sync_outbox", Integer.class));
        assertEquals("SYNCED", jdbcTemplate.queryForObject(
                "SELECT sync_status FROM `forward` WHERE id = 1", String.class));
    }

    @Test
    void recordsFailureForRetryWithoutLosingDesiredState() {
        outboxService.enqueueUpsert(1L, null, null);
        SyncTask task = outboxService.findDueTasks(10).get(0);
        assertTrue(outboxService.claim(task.getId()));

        outboxService.fail(task, "node unavailable");

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM forward_sync_outbox WHERE id = ?",
                String.class,
                task.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT attempts FROM forward_sync_outbox WHERE id = ?",
                Integer.class,
                task.getId()));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT sync_status FROM `forward` WHERE id = 1", String.class));
    }
}
