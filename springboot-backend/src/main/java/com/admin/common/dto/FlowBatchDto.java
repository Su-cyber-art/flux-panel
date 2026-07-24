package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class FlowBatchDto {

    private String instanceId;

    private Long startedAt;

    private Long sequence;

    private List<FlowDto> items;
}
