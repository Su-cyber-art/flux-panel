package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("forward_hop_port")
public class ForwardHopPort implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long forwardId;

    private Long nodeId;

    private Integer hopOrder;

    private Integer port;

    private Long createdTime;

    private Long updatedTime;
}
