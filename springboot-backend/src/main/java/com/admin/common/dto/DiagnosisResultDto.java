package com.admin.common.dto;

import lombok.Data;

/**
 * 单条诊断结果。
 *
 * <p>category 用于前端分组展示：</p>
 * <ul>
 *   <li>LISTENER —— 入口服务监听探测（转发的入口端口是否真的在监听）</li>
 *   <li>HOP      —— 逐跳真实 TCP 建连 + 延迟</li>
 *   <li>TARGET   —— 出口/入口节点到最终目标地址的真实 TCP 建连</li>
 *   <li>LOOPBACK —— 端到端真实数据回环（字节级完整性校验）</li>
 * </ul>
 */
@Data
public class DiagnosisResultDto {

    /** 结果分类：LISTENER / HOP / TARGET / LOOPBACK */
    private String category;

    private Long nodeId;
    private String nodeName;
    private String targetIp;
    private Integer targetPort;
    private String description;

    private boolean success;
    private String message;

    /** 延迟指标（毫秒），-1 表示不可用 */
    private double averageTime = -1.0;
    private double minTime = -1.0;
    private double maxTime = -1.0;
    private double jitter = -1.0;

    /** 丢包/失败率（%），100 表示完全不可达 */
    private double packetLoss = 100.0;

    // ===== 数据回环专属指标 =====
    /** 回环总轮次 */
    private Integer rounds;
    /** 成功往返并校验一致的轮次 */
    private Integer okRounds;
    /** 已校验的字节数 */
    private Long bytesVerified;
    /** 数据是否逐字节完整一致 */
    private Boolean integrityOk;

    private long timestamp = System.currentTimeMillis();

    public static DiagnosisResultDto of(String category, Long nodeId, String nodeName,
                                        String targetIp, Integer targetPort, String description) {
        DiagnosisResultDto d = new DiagnosisResultDto();
        d.category = category;
        d.nodeId = nodeId;
        d.nodeName = nodeName;
        d.targetIp = targetIp;
        d.targetPort = targetPort;
        d.description = description;
        return d;
    }

    public DiagnosisResultDto fail(String message) {
        this.success = false;
        this.message = message;
        this.averageTime = -1.0;
        this.minTime = -1.0;
        this.maxTime = -1.0;
        this.jitter = -1.0;
        this.packetLoss = 100.0;
        return this;
    }
}
