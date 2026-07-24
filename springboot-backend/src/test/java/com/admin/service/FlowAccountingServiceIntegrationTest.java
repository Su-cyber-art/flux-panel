package com.admin.service;

import com.admin.common.dto.FlowBatchDto;
import com.admin.common.dto.FlowDto;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowAccountingServiceIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private FlowAccountingService accountingService;
    private Node node;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:flow-accounting;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                        + "DATABASE_TO_LOWER=TRUE",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        recreateSchema();

        ForwardService forwardService = mock(ForwardService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        accountingService = new FlowAccountingService(
                jdbcTemplate, forwardService, tunnelService, userTunnelService);

        node = new Node();
        node.setId(9L);

        Forward forward = new Forward();
        forward.setId(101L);
        forward.setUserId(7);
        forward.setTunnelId(11);

        Tunnel tunnel = new Tunnel();
        tunnel.setId(11L);
        tunnel.setInNodeId(9L);
        tunnel.setTrafficRatio(BigDecimal.ONE);
        tunnel.setFlow(2);

        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(501);
        userTunnel.setUserId(7);
        userTunnel.setTunnelId(11);

        when(forwardService.getById(101L)).thenReturn(forward);
        when(tunnelService.getById(11)).thenReturn(tunnel);
        when(userTunnelService.getById(501)).thenReturn(userTunnel);
    }

    @Test
    void deduplicatesSequenceAndBooksOnlyAbsoluteCounterDelta() {
        FlowAccountingService.AccountingResult first =
                account(batch(1L, 100L, 100L, 200L));
        FlowAccountingService.AccountingResult duplicate =
                account(batch(1L, 100L, 100L, 200L));
        FlowAccountingService.AccountingResult second =
                account(batch(2L, 100L, 130L, 250L));
        FlowAccountingService.AccountingResult recreatedService =
                account(batch(3L, 200L, 20L, 30L));

        assertFalse(first.isDuplicate());
        assertTrue(duplicate.isDuplicate());
        assertFalse(second.isDuplicate());
        assertFalse(recreatedService.isDuplicate());
        assertFlow("forward", "id", 101, 560L, 300L);
        assertFlow("user", "id", 7, 560L, 300L);
        assertFlow("user_tunnel", "id", 501, 560L, 300L);
        assertEquals(3L, jdbcTemplate.queryForObject(
                "SELECT last_sequence FROM flow_report_stream WHERE node_id = 9",
                Long.class));
    }

    @Test
    void rollsBackEveryAccountingWriteWhenOneTargetFails() {
        jdbcTemplate.execute("DROP TABLE user_tunnel");

        assertThrows(RuntimeException.class,
                () -> account(batch(1L, 100L, 100L, 200L)));

        assertFlow("forward", "id", 101, 0L, 0L);
        assertFlow("user", "id", 7, 0L, 0L);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flow_report_stream",
                Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flow_report_cursor",
                Integer.class));
    }

    private FlowAccountingService.AccountingResult account(FlowBatchDto batch) {
        return transactionTemplate.execute(status ->
                accountingService.accountBatch(node, batch));
    }

    private FlowBatchDto batch(
            long sequence, long generation, long up, long down) {
        FlowDto item = new FlowDto();
        item.setN("101_7_501");
        item.setG(generation);
        item.setU(up);
        item.setD(down);

        FlowBatchDto batch = new FlowBatchDto();
        batch.setInstanceId("instance-a");
        batch.setStartedAt(1000L);
        batch.setSequence(sequence);
        batch.setItems(List.of(item));
        return batch;
    }

    private void assertFlow(
            String table, String idColumn, int id, long inFlow, long outFlow) {
        assertEquals(inFlow, jdbcTemplate.queryForObject(
                "SELECT in_flow FROM `" + table + "` WHERE " + idColumn + " = ?",
                Long.class,
                id));
        assertEquals(outFlow, jdbcTemplate.queryForObject(
                "SELECT out_flow FROM `" + table + "` WHERE " + idColumn + " = ?",
                Long.class,
                id));
    }

    private void recreateSchema() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE flow_report_stream (
                    node_id BIGINT PRIMARY KEY,
                    instance_id VARCHAR(64) NOT NULL,
                    started_at BIGINT NOT NULL,
                    last_sequence BIGINT NOT NULL,
                    created_time BIGINT NOT NULL,
                    updated_time BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE flow_report_cursor (
                    node_id BIGINT NOT NULL,
                    service_name VARCHAR(160) NOT NULL,
                    instance_id VARCHAR(64) NOT NULL,
                    service_generation BIGINT NOT NULL,
                    last_up BIGINT NOT NULL,
                    last_down BIGINT NOT NULL,
                    created_time BIGINT NOT NULL,
                    updated_time BIGINT NOT NULL,
                    PRIMARY KEY (node_id, service_name)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE `forward` (
                    id BIGINT PRIMARY KEY,
                    in_flow BIGINT NOT NULL,
                    out_flow BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE `user` (
                    id BIGINT PRIMARY KEY,
                    in_flow BIGINT NOT NULL,
                    out_flow BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE user_tunnel (
                    id BIGINT PRIMARY KEY,
                    in_flow BIGINT NOT NULL,
                    out_flow BIGINT NOT NULL
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO `forward` (id, in_flow, out_flow) VALUES (101, 0, 0)");
        jdbcTemplate.update(
                "INSERT INTO `user` (id, in_flow, out_flow) VALUES (7, 0, 0)");
        jdbcTemplate.update(
                "INSERT INTO user_tunnel (id, in_flow, out_flow) VALUES (501, 0, 0)");
    }
}
