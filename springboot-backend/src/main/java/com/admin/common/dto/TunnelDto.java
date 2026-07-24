package com.admin.common.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class TunnelDto {
    
    @NotBlank(message = "隧道名称不能为空")
    private String name;
    
    @NotNull(message = "入口节点不能为空")
    private Long inNodeId;

    // 出口节点ID，当type=1时可以为空，会自动设置为入口节点ID
    private Long outNodeId;

    // Ordered relay nodes between the entry and exit nodes.
    private List<Long> chainNodeIds = new ArrayList<>();
    
    @NotNull(message = "隧道类型不能为空")
    private Integer type;
    
    @NotNull(message = "流量计算类型不能为空")
    private Integer flow;
    
    // 流量倍率，默认为1.0
    @DecimalMin(value = "0.0", inclusive = false, message = "流量倍率必须大于0.0")
    @DecimalMax(value = "100.0", message = "流量倍率不能大于100.0")
    private BigDecimal trafficRatio;

    private String interfaceName;
    
    // 协议类型，默认为tls
    private String protocol;
    
    // TCP监听地址，默认为0.0.0.0
    private String tcpListenAddr = "0.0.0.0";
    
    // UDP监听地址，默认为0.0.0.0
    private String udpListenAddr = "0.0.0.0";
}
