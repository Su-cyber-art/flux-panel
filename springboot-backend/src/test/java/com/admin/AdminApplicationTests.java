package com.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminApplicationTests {

    @Test
    void exposesApplicationEntryPoint() {
        assertNotNull(AdminApplication.class);
    }
}
