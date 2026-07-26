package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordUtilTest {

    @Test
    void encodesNewPasswordsWithBcrypt() {
        String encoded = PasswordUtil.encode("correct horse battery staple");

        assertTrue(encoded.startsWith("$2"));
        assertNotEquals("correct horse battery staple", encoded);
        assertTrue(PasswordUtil.matches("correct horse battery staple", encoded));
        assertFalse(PasswordUtil.matches("wrong password", encoded));
        assertFalse(PasswordUtil.needsUpgrade(encoded));
    }

    @Test
    void acceptsLegacyMd5AndMarksItForUpgrade() {
        String legacyHash = Md5Util.md5("legacy-password");

        assertTrue(PasswordUtil.matches("legacy-password", legacyHash));
        assertFalse(PasswordUtil.matches("wrong password", legacyHash));
        assertTrue(PasswordUtil.needsUpgrade(legacyHash));
    }
}
