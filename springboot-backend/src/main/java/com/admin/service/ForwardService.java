package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardPortCheckDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.baomidou.mybatisplus.spring.service.IService;
import java.util.Map;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface ForwardService extends IService<Forward> {

    /**
     * 创建端口转发
     * @param forwardDto 转发数据
     * @return 结果
     */
    R createForward(ForwardDto forwardDto);

    /**
     * 获取端口转发列表
     * @return 结果
     */
    R getAllForwards();

    /**
     * 更新端口转发
     * @param forwardUpdateDto 更新数据
     * @return 结果
     */
    R updateForward(ForwardUpdateDto forwardUpdateDto);

    /**
     * 检查用户指定的入口端口是否可用
     * @param portCheckDto 端口校验参数
     * @return 可用状态和具体原因
     */
    R checkPortAvailability(ForwardPortCheckDto portCheckDto);

    /**
     * 删除端口转发
     * @param id 转发ID
     * @return 结果
     */
    R deleteForward(Long id);

    /**
     * 强制删除端口转发
     * 跳过GOST节点验证，直接删除数据库记录
     * @param id 转发ID
     * @return 结果
     */
    R forceDeleteForward(Long id);

    /**
     * 暂停转发服务
     * @param id 转发ID
     * @return 结果
     */
    R pauseForward(Long id);

    /**
     * 恢复转发服务
     * @param id 转发ID
     * @return 结果
     */
    R resumeForward(Long id);

    /**
     * Internal desired-state status change used by quota enforcement.
     */
    void requestForwardStatus(Long id, int status);

    /**
     * Reconcile the current database state to GOST.
     */
    R reconcileForward(Long id, Long oldTunnelId, String oldServiceName);

    /**
     * Reconcile a pending deletion and remove the database record afterwards.
     */
    R reconcileForwardDeletion(Long id, Long tunnelId, String serviceName);

    /**
     * 转发诊断功能
     * @param id 转发ID
     * @return 诊断结果
     */
    R diagnoseForward(Long id);

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数
     * @return 更新结果
     */
    R updateForwardOrder(Map<String, Object> params);


    void updateForwardA(Forward forward);
}
