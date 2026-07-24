-- This migration intentionally fails when legacy data contains duplicate
-- usernames, node secrets, user/tunnel grants, or active socket assignments.
-- Resolve those ambiguities before restarting the backend; silently choosing
-- one row would make authentication, accounting, or port ownership unsafe.

ALTER TABLE `forward`
    ADD COLUMN `sync_status` VARCHAR(16) NOT NULL DEFAULT 'SYNCED' AFTER `inx`,
    ADD COLUMN `sync_error` VARCHAR(2000) NULL AFTER `sync_status`,
    ADD COLUMN `delete_requested` TINYINT(1) NOT NULL DEFAULT 0 AFTER `sync_error`,
    ADD COLUMN `port_reservation_token` VARCHAR(36) CHARACTER SET ascii NULL
        AFTER `delete_requested`;

CREATE TABLE `flow_report_stream` (
    `node_id` INT(10) NOT NULL,
    `instance_id` VARCHAR(64) CHARACTER SET ascii NOT NULL,
    `started_at` BIGINT(20) NOT NULL,
    `last_sequence` BIGINT(20) NOT NULL DEFAULT 0,
    `created_time` BIGINT(20) NOT NULL,
    `updated_time` BIGINT(20) NOT NULL,
    PRIMARY KEY (`node_id`),
    CONSTRAINT `fk_flow_stream_node`
        FOREIGN KEY (`node_id`) REFERENCES `node` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `flow_report_cursor` (
    `node_id` INT(10) NOT NULL,
    `service_name` VARCHAR(160) NOT NULL,
    `instance_id` VARCHAR(64) CHARACTER SET ascii NOT NULL,
    `service_generation` BIGINT(20) NOT NULL DEFAULT 0,
    `last_up` BIGINT(20) NOT NULL DEFAULT 0,
    `last_down` BIGINT(20) NOT NULL DEFAULT 0,
    `created_time` BIGINT(20) NOT NULL,
    `updated_time` BIGINT(20) NOT NULL,
    PRIMARY KEY (`node_id`, `service_name`),
    KEY `idx_flow_cursor_updated` (`updated_time`),
    CONSTRAINT `fk_flow_cursor_node`
        FOREIGN KEY (`node_id`) REFERENCES `node` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `port_reservation` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
    `node_id` INT(10) NOT NULL,
    `protocol` VARCHAR(16) CHARACTER SET ascii NOT NULL,
    `port` INT(10) NOT NULL,
    `forward_id` INT(10) NULL,
    `owner_token` VARCHAR(36) CHARACTER SET ascii NOT NULL,
    `purpose` VARCHAR(16) CHARACTER SET ascii NOT NULL,
    `hop_order` INT(10) NOT NULL DEFAULT 0,
    `created_time` BIGINT(20) NOT NULL,
    `updated_time` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_protocol_port` (`node_id`, `protocol`, `port`),
    KEY `idx_port_reservation_forward` (`forward_id`),
    KEY `idx_port_reservation_owner` (`owner_token`),
    CONSTRAINT `fk_port_reservation_node`
        FOREIGN KEY (`node_id`) REFERENCES `node` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_port_reservation_forward`
        FOREIGN KEY (`forward_id`) REFERENCES `forward` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `forward_sync_outbox` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
    `forward_id` INT(10) NOT NULL,
    `operation` VARCHAR(16) CHARACTER SET ascii NOT NULL,
    `old_tunnel_id` INT(10) NULL,
    `old_service_name` VARCHAR(160) NULL,
    `status` VARCHAR(16) CHARACTER SET ascii NOT NULL,
    `attempts` INT(10) NOT NULL DEFAULT 0,
    `next_attempt_at` BIGINT(20) NOT NULL,
    `locked_at` BIGINT(20) NULL,
    `last_error` VARCHAR(2000) NULL,
    `created_time` BIGINT(20) NOT NULL,
    `updated_time` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_forward_outbox_due`
        (`status`, `next_attempt_at`, `locked_at`, `id`),
    KEY `idx_forward_outbox_forward` (`forward_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `user`
    ADD UNIQUE KEY `uk_user_username` (`user`),
    ADD KEY `idx_user_status_exp` (`status`, `exp_time`);

ALTER TABLE `node`
    ADD UNIQUE KEY `uk_node_secret` (`secret`),
    ADD KEY `idx_node_status` (`status`);

ALTER TABLE `user_tunnel`
    ADD UNIQUE KEY `uk_user_tunnel` (`user_id`, `tunnel_id`),
    ADD KEY `idx_user_tunnel_status_exp`
        (`user_id`, `status`, `exp_time`);

ALTER TABLE `forward`
    ADD KEY `idx_forward_user_status`
        (`user_id`, `delete_requested`, `status`),
    ADD KEY `idx_forward_tunnel_status`
        (`tunnel_id`, `delete_requested`, `status`),
    ADD KEY `idx_forward_updated` (`updated_time`);

ALTER TABLE `tunnel`
    ADD KEY `idx_tunnel_in_status` (`in_node_id`, `status`),
    ADD KEY `idx_tunnel_out_status` (`out_node_id`, `status`);

ALTER TABLE `speed_limit`
    ADD KEY `idx_speed_limit_tunnel` (`tunnel_id`, `status`);

ALTER TABLE `statistics_flow`
    ADD KEY `idx_statistics_user_created` (`user_id`, `created_time`);

INSERT INTO `port_reservation`
    (`node_id`, `protocol`, `port`, `forward_id`, `owner_token`,
     `purpose`, `hop_order`, `created_time`, `updated_time`)
SELECT
    t.`in_node_id`,
    CASE
        WHEN LOWER(t.`protocol`) LIKE '%udp%' THEN 'udp'
        ELSE 'tcp'
    END,
    f.`in_port`,
    f.`id`,
    CONCAT('legacy-', f.`id`),
    'ENTRY',
    0,
    f.`created_time`,
    f.`updated_time`
FROM `forward` f
INNER JOIN `tunnel` t ON t.`id` = f.`tunnel_id`;

INSERT INTO `port_reservation`
    (`node_id`, `protocol`, `port`, `forward_id`, `owner_token`,
     `purpose`, `hop_order`, `created_time`, `updated_time`)
SELECT
    hp.`node_id`,
    CASE
        WHEN LOWER(t.`protocol`) LIKE '%udp%' THEN 'udp'
        ELSE 'tcp'
    END,
    hp.`port`,
    hp.`forward_id`,
    CONCAT('legacy-', hp.`forward_id`),
    'RELAY',
    hp.`hop_order`,
    hp.`created_time`,
    hp.`updated_time`
FROM `forward_hop_port` hp
INNER JOIN `forward` f ON f.`id` = hp.`forward_id`
INNER JOIN `tunnel` t ON t.`id` = f.`tunnel_id`;

INSERT INTO `port_reservation`
    (`node_id`, `protocol`, `port`, `forward_id`, `owner_token`,
     `purpose`, `hop_order`, `created_time`, `updated_time`)
SELECT
    t.`out_node_id`,
    CASE
        WHEN LOWER(t.`protocol`) LIKE '%udp%' THEN 'udp'
        ELSE 'tcp'
    END,
    f.`out_port`,
    f.`id`,
    CONCAT('legacy-', f.`id`),
    'RELAY',
    0,
    f.`created_time`,
    f.`updated_time`
FROM `forward` f
INNER JOIN `tunnel` t ON t.`id` = f.`tunnel_id`
WHERE t.`type` = 2
  AND f.`out_port` IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM `forward_hop_port` hp
      WHERE hp.`forward_id` = f.`id`
        AND hp.`node_id` = t.`out_node_id`
  );

UPDATE `forward`
SET `port_reservation_token` = CONCAT('legacy-', `id`);
