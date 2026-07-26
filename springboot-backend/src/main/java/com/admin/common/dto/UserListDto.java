package com.admin.common.dto;

import com.admin.entity.User;
import lombok.Data;

@Data
public class UserListDto {

    private Long id;
    private Long createdTime;
    private Long updatedTime;
    private Integer status;
    private String user;
    private Integer roleId;
    private Long expTime;
    private Long flow;
    private Long inFlow;
    private Long outFlow;
    private Integer num;
    private Long flowResetTime;

    public static UserListDto from(User user) {
        UserListDto dto = new UserListDto();
        dto.setId(user.getId());
        dto.setCreatedTime(user.getCreatedTime());
        dto.setUpdatedTime(user.getUpdatedTime());
        dto.setStatus(user.getStatus());
        dto.setUser(user.getUser());
        dto.setRoleId(user.getRoleId());
        dto.setExpTime(user.getExpTime());
        dto.setFlow(user.getFlow());
        dto.setInFlow(user.getInFlow());
        dto.setOutFlow(user.getOutFlow());
        dto.setNum(user.getNum());
        dto.setFlowResetTime(user.getFlowResetTime());
        return dto;
    }
}
