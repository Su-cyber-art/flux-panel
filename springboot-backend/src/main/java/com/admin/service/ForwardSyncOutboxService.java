package com.admin.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ForwardSyncOutboxService {

    public static final String OPERATION_UPSERT = "UPSERT";
    public static final String OPERATION_DELETE = "DELETE";

    private static final long LOCK_TIMEOUT_MS = 60_000L;

    private final JdbcTemplate jdbcTemplate;

    public void enqueueUpsert(
            Long forwardId, Long oldTunnelId, String oldServiceName) {
        enqueue(forwardId, OPERATION_UPSERT, oldTunnelId, oldServiceName);
    }

    public void enqueueDelete(
            Long forwardId, Long tunnelId, String serviceName) {
        enqueue(forwardId, OPERATION_DELETE, tunnelId, serviceName);
    }

    private void enqueue(
            Long forwardId, String operation, Long oldTunnelId, String oldServiceName) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO forward_sync_outbox
                    (forward_id, operation, old_tunnel_id, old_service_name,
                     status, attempts, next_attempt_at, created_time, updated_time)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """, forwardId, operation, oldTunnelId, oldServiceName, now, now, now);
        jdbcTemplate.update("""
                UPDATE `forward`
                SET sync_status = 'PENDING', sync_error = NULL, updated_time = ?
                WHERE id = ?
                """, now, forwardId);
    }

    public List<SyncTask> findDueTasks(int limit) {
        long now = System.currentTimeMillis();
        long expiredLock = now - LOCK_TIMEOUT_MS;
        return jdbcTemplate.query("""
                SELECT id, forward_id, operation, old_tunnel_id, old_service_name, attempts
                FROM forward_sync_outbox
                WHERE (status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?)
                   OR (status = 'PROCESSING' AND locked_at < ?)
                ORDER BY id
                LIMIT ?
                """, (rs, rowNum) -> new SyncTask(
                rs.getLong("id"),
                rs.getLong("forward_id"),
                rs.getString("operation"),
                nullableLong(rs.getObject("old_tunnel_id")),
                rs.getString("old_service_name"),
                rs.getInt("attempts")), now, expiredLock, limit);
    }

    public boolean claim(long taskId) {
        long now = System.currentTimeMillis();
        long expiredLock = now - LOCK_TIMEOUT_MS;
        return jdbcTemplate.update("""
                UPDATE forward_sync_outbox
                SET status = 'PROCESSING', locked_at = ?, updated_time = ?
                WHERE id = ?
                  AND ((status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?)
                    OR (status = 'PROCESSING' AND locked_at < ?))
                """, now, now, taskId, now, expiredLock) == 1;
    }

    @Transactional
    public void complete(SyncTask task) {
        jdbcTemplate.update("DELETE FROM forward_sync_outbox WHERE id = ?", task.id);
        if (OPERATION_UPSERT.equals(task.operation)) {
            jdbcTemplate.update("""
                    UPDATE `forward`
                    SET sync_status = 'SYNCED', sync_error = NULL, updated_time = ?
                    WHERE id = ? AND delete_requested = 0
                      AND NOT EXISTS (
                          SELECT 1
                          FROM forward_sync_outbox pending
                          WHERE pending.forward_id = ?
                      )
                    """, System.currentTimeMillis(), task.forwardId, task.forwardId);
        }
    }

    @Transactional
    public void fail(SyncTask task, String error) {
        int attempts = task.attempts + 1;
        long delay = Math.min(300_000L, 2_000L << Math.min(attempts - 1, 16));
        long now = System.currentTimeMillis();
        String message = truncate(error, 2000);
        jdbcTemplate.update("""
                UPDATE forward_sync_outbox
                SET status = 'FAILED', attempts = ?, next_attempt_at = ?,
                    locked_at = NULL, last_error = ?, updated_time = ?
                WHERE id = ?
                """, attempts, now + delay, message, now, task.id);
        jdbcTemplate.update("""
                UPDATE `forward`
                SET sync_status = 'FAILED', sync_error = ?, updated_time = ?
                WHERE id = ?
                """, message, now, task.forwardId);
    }

    public void removeByForwardId(Long forwardId) {
        if (forwardId != null) {
            jdbcTemplate.update(
                    "DELETE FROM forward_sync_outbox WHERE forward_id = ?", forwardId);
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "节点同步失败";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    @Getter
    public static class SyncTask {
        private final long id;
        private final long forwardId;
        private final String operation;
        private final Long oldTunnelId;
        private final String oldServiceName;
        private final int attempts;

        private SyncTask(
                long id,
                long forwardId,
                String operation,
                Long oldTunnelId,
                String oldServiceName,
                int attempts) {
            this.id = id;
            this.forwardId = forwardId;
            this.operation = operation;
            this.oldTunnelId = oldTunnelId;
            this.oldServiceName = oldServiceName;
            this.attempts = attempts;
        }
    }
}
