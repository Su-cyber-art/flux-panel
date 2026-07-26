package com.admin.common.dto;

import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class UserListDtoTest {

    @Test
    void userResponsesNeverSerializePasswordHashes() {
        User user = new User();
        user.setId(1L);
        user.setUser("alice");
        user.setPwd("sensitive-password-hash");

        String entityJson = JSON.toJSONString(user);
        String listDtoJson = JSON.toJSONString(UserListDto.from(user));

        assertFalse(entityJson.contains("pwd"));
        assertFalse(entityJson.contains("sensitive-password-hash"));
        assertFalse(listDtoJson.contains("pwd"));
        assertFalse(listDtoJson.contains("sensitive-password-hash"));
    }
}
