package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class ForwardPortCheckDto {

    @NotNull(message = "隧道ID不能为空")
    private Integer tunnelId;

    @NotNull(message = "入口端口不能为空")
    @Min(value = 1, message = "端口号不能小于1")
    @Max(value = 65535, message = "端口号不能大于65535")
    private Integer inPort;

    private Long excludeForwardId;
}
