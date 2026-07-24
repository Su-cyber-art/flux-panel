package com.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:flux;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.flyway.enabled=false",
                "jwt-secret=test-secret",
                "log-dir=${java.io.tmpdir}/flux-panel-test-logs",
                "forward.reconciler.initial-delay-ms=600000"
        })
class AdminApplicationTests {

    @Test
    void contextLoads() {
    }
}
