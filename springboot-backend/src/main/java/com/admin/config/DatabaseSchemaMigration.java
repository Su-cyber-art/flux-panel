package com.admin.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class DatabaseSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tunnel_hop (
                    id INT(10) NOT NULL AUTO_INCREMENT,
                    tunnel_id INT(10) NOT NULL,
                    node_id INT(10) NOT NULL,
                    hop_order INT(10) NOT NULL,
                    created_time BIGINT(20) NOT NULL,
                    updated_time BIGINT(20) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_tunnel_hop_order (tunnel_id, hop_order),
                    UNIQUE KEY uk_tunnel_hop_node (tunnel_id, node_id),
                    KEY idx_tunnel_hop_node (node_id),
                    CONSTRAINT fk_tunnel_hop_tunnel FOREIGN KEY (tunnel_id) REFERENCES `tunnel` (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS forward_hop_port (
                    id INT(10) NOT NULL AUTO_INCREMENT,
                    forward_id INT(10) NOT NULL,
                    node_id INT(10) NOT NULL,
                    hop_order INT(10) NOT NULL,
                    port INT(10) NOT NULL,
                    created_time BIGINT(20) NOT NULL,
                    updated_time BIGINT(20) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_forward_hop_node (forward_id, node_id),
                    UNIQUE KEY uk_forward_hop_order (forward_id, hop_order),
                    UNIQUE KEY uk_node_hop_port (node_id, port),
                    CONSTRAINT fk_forward_hop_forward FOREIGN KEY (forward_id) REFERENCES `forward` (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        jdbcTemplate.update("""
                INSERT IGNORE INTO forward_hop_port
                    (forward_id, node_id, hop_order, port, created_time, updated_time)
                SELECT f.id, t.out_node_id, 0, f.out_port, f.created_time, f.updated_time
                FROM `forward` f
                INNER JOIN `tunnel` t ON t.id = f.tunnel_id
                WHERE t.type = 2 AND f.out_port IS NOT NULL
                """);
    }
}
