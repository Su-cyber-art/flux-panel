package com.admin.service;

import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortReservationServiceIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private PortReservationService reservationService;
    private Tunnel tunnel;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:port-reservation;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                        + "DATABASE_TO_LOWER=TRUE",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE `forward` (
                    id BIGINT PRIMARY KEY,
                    port_reservation_token VARCHAR(36)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE port_reservation (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    node_id BIGINT NOT NULL,
                    protocol VARCHAR(16) NOT NULL,
                    port INT NOT NULL,
                    forward_id BIGINT NULL,
                    owner_token VARCHAR(36) NOT NULL,
                    purpose VARCHAR(16) NOT NULL,
                    hop_order INT NOT NULL,
                    created_time BIGINT NOT NULL,
                    updated_time BIGINT NOT NULL,
                    CONSTRAINT uk_node_protocol_port
                        UNIQUE (node_id, protocol, port)
                )
                """);

        NodeService nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ForwardHopPortService hopPortService = mock(ForwardHopPortService.class);
        reservationService = new PortReservationService(
                jdbcTemplate, nodeService, tunnelService, hopPortService);

        Node node = new Node();
        node.setId(9L);
        node.setName("entry");
        node.setPortSta(1000);
        node.setPortEnd(1002);
        when(nodeService.getNodeById(9L)).thenReturn(node);

        tunnel = new Tunnel();
        tunnel.setInNodeId(9L);
        tunnel.setProtocol("tls");
        tunnel.setType(1);
    }

    @Test
    void uniqueConstraintAtomicallyRejectsASecondClaim() {
        PortReservationService.PortAllocation first =
                reservationService.reserveForCreate(tunnel, 1000);
        PortReservationService.PortAllocation duplicate =
                reservationService.reserveForCreate(tunnel, 1000);
        PortReservationService.PortAllocation automatic =
                reservationService.reserveForCreate(tunnel, null);

        assertFalse(first.isHasError());
        assertTrue(duplicate.isHasError());
        assertEquals(1001, automatic.getInPort());
    }

    @Test
    void retainsOldPortUntilReconciliationSucceeds() {
        PortReservationService.PortAllocation initial =
                reservationService.reserveForCreate(tunnel, 1000);
        jdbcTemplate.update(
                "INSERT INTO `forward` (id, port_reservation_token) VALUES (1, ?)",
                initial.getToken());
        reservationService.bindToForward(initial, 1L);

        com.admin.entity.Forward existing = new com.admin.entity.Forward();
        existing.setId(1L);
        existing.setInPort(1000);
        PortReservationService.PortAllocation updated =
                reservationService.reserveForUpdate(tunnel, 1001, existing, false);
        jdbcTemplate.update(
                "UPDATE `forward` SET port_reservation_token = ? WHERE id = 1",
                updated.getToken());
        reservationService.bindToForward(updated, 1L);

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM port_reservation WHERE forward_id = 1",
                Integer.class));

        reservationService.releaseObsolete(1L, updated.getToken());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM port_reservation WHERE forward_id = 1",
                Integer.class));
        assertEquals(1001, jdbcTemplate.queryForObject(
                "SELECT port FROM port_reservation WHERE forward_id = 1",
                Integer.class));
    }
}
