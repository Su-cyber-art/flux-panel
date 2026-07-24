package com.admin.common.dto;


import lombok.Data;

@Data
public class FlowDto {
    // 转发id_类型
    private String n;

    // 服务创建代次
    private Long g;

    // 上传流量
    private Long u;

    // 下载流量
    private Long d;
}
