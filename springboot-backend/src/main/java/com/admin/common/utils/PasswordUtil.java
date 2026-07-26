package com.admin.common.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class PasswordUtil {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER =
            new BCryptPasswordEncoder(12);
    private static final String BCRYPT_PREFIX = "$2";

    private PasswordUtil() {
    }

    public static String encode(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return PASSWORD_ENCODER.encode(password);
    }

    public static boolean matches(String password, String encodedPassword) {
        if (password == null || encodedPassword == null) {
            return false;
        }
        if (isBcrypt(encodedPassword)) {
            return PASSWORD_ENCODER.matches(password, encodedPassword);
        }
        return encodedPassword.equals(Md5Util.md5(password));
    }

    public static boolean needsUpgrade(String encodedPassword) {
        return !isBcrypt(encodedPassword);
    }

    private static boolean isBcrypt(String encodedPassword) {
        return encodedPassword != null && encodedPassword.startsWith(BCRYPT_PREFIX);
    }
}
