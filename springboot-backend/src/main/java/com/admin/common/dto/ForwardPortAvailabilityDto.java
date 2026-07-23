package com.admin.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ForwardPortAvailabilityDto {

    private boolean available;
    private String message;
    private Integer port;
    private Integer minPort;
    private Integer maxPort;
}
