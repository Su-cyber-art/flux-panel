package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardPortAvailabilityDto;
import com.admin.common.dto.ForwardPortCheckDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    // 常量定义
    private static final String GOST_SUCCESS_MSG = "OK";
    private static final String GOST_NOT_FOUND_MSG = "not found";
    private static final int ADMIN_ROLE_ID = 0;
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int FORWARD_STATUS_ACTIVE = 1;
    private static final int FORWARD_STATUS_PAUSED = 0;
    private static final int FORWARD_STATUS_ERROR = -1;
    private static final int FORWARD_STATUS_DELETING = -2;
    private static final int TUNNEL_STATUS_ACTIVE = 1;
    private static final String SYNC_STATUS_PENDING = "PENDING";

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    @Resource
    ForwardHopPortService forwardHopPortService;

    @Resource
    PortReservationService portReservationService;

    @Resource
    ForwardSyncOutboxService forwardSyncOutboxService;

    @Override
    @Transactional
    public R createForward(ForwardDto forwardDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法创建转发");
        }

        // 3. 普通用户权限和限制检查
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        // 4. 在预留端口前验证节点引用
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 5. 在数据库中原子预留入口及中转端口
        PortReservationService.PortAllocation portAllocation =
                portReservationService.reserveForCreate(tunnel, forwardDto.getInPort());
        if (portAllocation.isHasError()) {
            return R.err(portAllocation.getErrorMessage());
        }

        // 6. 创建并保存期望状态
        Forward forward = createForwardEntity(forwardDto, currentUser, portAllocation);
        if (!this.save(forward)) {
            portReservationService.releaseUnbound(portAllocation);
            return R.err("端口转发创建失败");
        }
        portReservationService.bindToForward(portAllocation, forward.getId());
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            forwardHopPortService.replaceForwardPorts(
                    forward.getId(), tunnelService.getRelayNodeIds(tunnel), portAllocation.getRelayPorts());
        }

        forwardSyncOutboxService.enqueueUpsert(forward.getId(), null, null);
        return R.ok("端口转发已保存，等待节点同步");
    }

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();

        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        return R.ok(forwardList);
    }

    @Override
    public R checkPortAvailability(ForwardPortCheckDto portCheckDto) {
        UserInfo currentUser = getCurrentUserInfo();
        Tunnel tunnel = validateTunnel(portCheckDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法校验端口");
        }

        if (currentUser.getRoleId() != ADMIN_ROLE_ID
                && getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue()) == null) {
            return R.err("你没有该隧道权限");
        }

        Long excludeForwardId = portCheckDto.getExcludeForwardId();
        if (excludeForwardId != null && validateForwardExists(excludeForwardId, currentUser) == null) {
            return R.err("转发不存在或无权操作");
        }

        return R.ok(getInPortAvailability(tunnel, portCheckDto.getInPort(), excludeForwardId));
    }

    @Override
    @Transactional
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }

        // 3. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardUpdateDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法更新转发");
        }
        boolean tunnelChanged = isTunnelChanged(existForward, forwardUpdateDto);
        // 4. 检查权限和限制
        UserPermissionResult permissionResult = null;
        if (tunnelChanged) {
            if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
                // 管理员操作自己的转发时，不需要检查权限限制
                if (Objects.equals(currentUser.getUserId(), existForward.getUserId())) {
                    permissionResult = UserPermissionResult.success(null, null);
                } else {
                    // 管理员操作用户转发时，需要检查原用户是否有新隧道权限
                    // 获取原转发用户的信息
                    User originalUser = userService.getById(existForward.getUserId());
                    if (originalUser == null) {
                        return R.err("用户不存在");
                    }

                    // 检查原用户是否有新隧道权限
                    UserTunnel userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
                    if (userTunnel == null) {
                        return R.err("用户没有该隧道权限");
                    }

                    if (userTunnel.getStatus() != 1) {
                        return R.err("隧道被禁用");
                    }

                    // 检查隧道权限到期时间
                    if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
                        return R.err("用户的该隧道权限已到期");
                    }

                    // 检查原用户的流量和转发数量限制
                    R quotaCheckResult = checkForwardQuota(existForward.getUserId(), tunnel.getId().intValue(), userTunnel, originalUser, forwardUpdateDto.getId());
                    if (quotaCheckResult.getCode() != 0) {
                        return R.err("用户" + quotaCheckResult.getMsg());
                    }

                    permissionResult = UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
                }
            } else {
                // 普通用户检查自己的权限
                permissionResult = checkUserPermissions(currentUser, tunnel, forwardUpdateDto.getId());
                if (permissionResult.isHasError()) {
                    return R.err(permissionResult.getErrorMessage());
                }
            }
        }

        // 5. 获取UserTunnel（即使隧道未变化也需要获取，用于构建服务名称）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }
        boolean administratorOwnsForward = currentUser.getRoleId() == ADMIN_ROLE_ID
                && Objects.equals(currentUser.getUserId(), existForward.getUserId());
        Integer limiter = resolveLimiterForUpdate(userTunnel, administratorOwnsForward);

        // 6. 更新Forward对象和端口分配
        PortReservationService.PortAllocation portAllocation =
                portReservationService.reserveForUpdate(
                        tunnel, forwardUpdateDto.getInPort(), existForward, tunnelChanged);
        if (portAllocation.isHasError()) {
            return R.err(portAllocation.getErrorMessage());
        }
        Forward updatedForward = updateForwardEntity(forwardUpdateDto, existForward, portAllocation);

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            portReservationService.releaseUnbound(portAllocation);
            return R.err(nodeInfo.getErrorMessage());
        }

        Long oldTunnelId = null;
        String oldServiceName = null;
        if (tunnelChanged) {
            Tunnel oldTunnel = tunnelService.getById(existForward.getTunnelId());
            if (oldTunnel == null) {
                portReservationService.releaseUnbound(portAllocation);
                return R.err("原隧道不存在");
            }
            UserTunnel oldUserTunnel =
                    getUserTunnel(existForward.getUserId(), oldTunnel.getId().intValue());
            oldTunnelId = oldTunnel.getId();
            oldServiceName =
                    buildServiceName(existForward.getId(), existForward.getUserId(), oldUserTunnel);
        }

        // 8. 先提交数据库期望状态、端口和 outbox。
        updatedForward.setStatus(FORWARD_STATUS_ACTIVE);
        updatedForward.setSyncStatus(SYNC_STATUS_PENDING);
        updatedForward.setSyncError(null);
        updatedForward.setDeleteRequested(false);
        updatedForward.setPortReservationToken(portAllocation.getToken());
        if (!this.updateById(updatedForward)) {
            throw new IllegalStateException("保存端口转发期望状态失败");
        }
        portReservationService.bindToForward(portAllocation, updatedForward.getId());

        if (tunnelChanged) {
            forwardHopPortService.replaceForwardPorts(
                    updatedForward.getId(),
                    tunnelService.getRelayNodeIds(tunnel),
                    portAllocation.getRelayPorts());
        }
        forwardSyncOutboxService.enqueueUpsert(
                updatedForward.getId(), oldTunnelId, oldServiceName);
        return R.ok("端口转发已更新，等待节点同步");
    }

    @Override
    @Transactional
    public R deleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 权限检查（仅普通用户需要）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员删除用户记录时，需要获取对应的UserTunnel用于构建正确的服务名称
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        // 5. 保存删除期望；端口在节点配置删除成功前继续保持预留。
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        forward.setStatus(FORWARD_STATUS_DELETING);
        forward.setDeleteRequested(true);
        forward.setSyncStatus(SYNC_STATUS_PENDING);
        forward.setSyncError(null);
        forward.setUpdatedTime(System.currentTimeMillis());
        if (!this.updateById(forward)) {
            throw new IllegalStateException("保存删除期望状态失败");
        }
        forwardSyncOutboxService.enqueueDelete(
                forward.getId(), tunnel.getId(), serviceName);
        return R.ok("端口转发已标记删除，等待节点同步");
    }

    @Override
    @Transactional
    public R pauseForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_PAUSED, "暂停");
    }

    @Override
    @Transactional
    public R resumeForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_ACTIVE, "恢复");
    }

    @Override
    @Transactional
    public R forceDeleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限操作
        Forward forward = this.getById(id);
        if (forward == null
                || (currentUser.getRoleId() != ADMIN_ROLE_ID
                && !Objects.equals(currentUser.getUserId(), forward.getUserId()))) {
            return R.err("端口转发不存在");
        }

        // 3. 直接删除转发记录，跳过GOST服务删除
        forwardSyncOutboxService.removeByForwardId(id);
        portReservationService.releaseForward(id);
        if (!this.removeById(id)) {
            throw new IllegalStateException("删除端口转发记录失败");
        }
        forwardHopPortService.removeByForwardId(id);
        return R.ok("端口转发强制删除成功");
    }

    /**
     * 改变转发状态（暂停/恢复）
     */
    private R changeForwardStatus(Long id, int targetStatus, String operation) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 恢复服务时需要额外检查
        UserTunnel userTunnel = null;
        if (targetStatus == FORWARD_STATUS_ACTIVE) {
            if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
                return R.err("隧道已禁用，无法恢复服务");
            }

            // 普通用户需要检查流量和账户状态
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                R flowCheckResult = checkUserFlowLimits(currentUser.getUserId(), tunnel);
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }

                userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (userTunnel == null) {
                    return R.err("你没有该隧道权限");
                }

                if (userTunnel.getStatus() != 1) {
                    return R.err("隧道被禁用");
                }
            }
        }

        // 5. 权限检查（仅普通用户需要）
        if (currentUser.getRoleId() != ADMIN_ROLE_ID && userTunnel == null) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        }

        // 6. 确保获取UserTunnel用于构建服务名称（包括管理员用户）
        if (userTunnel == null) {
            // 通过forward记录获取原始的用户ID来查找UserTunnel
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        // 7. 保存期望状态并写入 outbox。
        forward.setStatus(targetStatus);
        forward.setSyncStatus(SYNC_STATUS_PENDING);
        forward.setSyncError(null);
        forward.setUpdatedTime(System.currentTimeMillis());
        if (!this.updateById(forward)) {
            throw new IllegalStateException("更新转发期望状态失败");
        }
        forwardSyncOutboxService.enqueueUpsert(forward.getId(), null, null);
        return R.ok("服务" + operation + "请求已保存，等待节点同步");
    }

    @Override
    @Transactional
    public void requestForwardStatus(Long id, int status) {
        Forward forward = this.getById(id);
        if (forward == null || Boolean.TRUE.equals(forward.getDeleteRequested())) {
            return;
        }
        if (Objects.equals(forward.getStatus(), status)) {
            return;
        }
        forward.setStatus(status);
        forward.setSyncStatus(SYNC_STATUS_PENDING);
        forward.setSyncError(null);
        forward.setUpdatedTime(System.currentTimeMillis());
        if (!this.updateById(forward)) {
            throw new IllegalStateException("更新转发期望状态失败");
        }
        forwardSyncOutboxService.enqueueUpsert(id, null, null);
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 获取入口节点信息
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return R.err("入口节点不存在");
        }


        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr().split(",");
        // 6. 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }

                DiagnosisResult result = performTcpPingDiagnosis(inNode, targetIp, targetPort, "转发->目标");
                results.add(result);
            }
        } else {
            NodeInfo nodeInfo = getRequiredNodes(tunnel);
            if (nodeInfo.isHasError()) {
                return R.err(nodeInfo.getErrorMessage());
            }
            Map<Long, Integer> relayPorts = getRelayPorts(forward, tunnel);
            List<Node> pathNodes = new ArrayList<>();
            pathNodes.add(inNode);
            pathNodes.addAll(nodeInfo.getRelayNodes());
            for (int i = 0; i < pathNodes.size() - 1; i++) {
                Node source = pathNodes.get(i);
                Node target = pathNodes.get(i + 1);
                Integer targetPort = relayPorts.get(target.getId());
                if (targetPort == null) {
                    return R.err("节点 " + target.getName() + " 缺少中转端口");
                }
                results.add(performTcpPingDiagnosis(source, target.getServerIp(), targetPort,
                        source.getName() + "->" + target.getName()));
            }

            Node outNode = nodeInfo.getOutNode();
            for (String remoteAddress : remoteAddresses) {
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }
                DiagnosisResult outToTargetResult = performTcpPingDiagnosis(outNode, targetIp, targetPort, "出口->目标");
                results.add(outToTargetResult);
            }

        }

        // 7. 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD ? "端口转发" : "隧道转发");
        diagnosisReport.put("pathNodeIds", tunnelService.getPathNodeIds(tunnel));
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    public R updateForwardOrder(Map<String, Object> params) {
        try {
            // 1. 获取当前用户信息
            UserInfo currentUser = getCurrentUserInfo();

            // 2. 验证参数
            if (!params.containsKey("forwards")) {
                return R.err("缺少forwards参数");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
            if (forwardsList == null || forwardsList.isEmpty()) {
                return R.err("forwards参数不能为空");
            }

            // 3. 验证用户权限（只能更新自己的转发）
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                // 普通用户只能更新自己的转发
                List<Long> forwardIds = forwardsList.stream()
                        .map(item -> Long.valueOf(item.get("id").toString()))
                        .collect(Collectors.toList());

                // 检查所有转发是否属于当前用户
                QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("id", forwardIds);
                queryWrapper.eq("user_id", currentUser.getUserId());

                long count = this.count(queryWrapper);
                if (count != forwardIds.size()) {
                    return R.err("只能更新自己的转发排序");
                }
            }

            // 4. 批量更新排序
            List<Forward> forwardsToUpdate = new ArrayList<>();
            for (Map<String, Object> forwardData : forwardsList) {
                Long id = Long.valueOf(forwardData.get("id").toString());
                Integer inx = Integer.valueOf(forwardData.get("inx").toString());

                Forward forward = new Forward();
                forward.setId(id);
                forward.setInx(inx);
                forwardsToUpdate.add(forward);
            }

            // 5. 执行批量更新
            boolean success = this.updateBatchById(forwardsToUpdate);
            if (success) {
                log.info("用户 {} 更新了 {} 个转发的排序", currentUser.getUserName(), forwardsToUpdate.size());
                return R.ok("排序更新成功");
            } else {
                return R.err("排序更新失败");
            }

        } catch (Exception e) {
            log.error("更新转发排序失败", e);
            return R.err("更新排序时发生错误: " + e.getMessage());
        }
    }

    /**
     * 从地址字符串中提取IP地址
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    /**
     * 从地址字符串中提取端口号
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    /**
     * 执行TCP ping诊断
     *
     * @param node        执行TCP ping的节点
     * @param targetIp    目标IP地址
     * @param port        目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 2);
            tcpPingData.put("timeout", 3000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    /**
     * 获取当前用户信息
     */
    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    /**
     * 验证隧道是否存在
     */
    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    /**
     * 验证转发是否存在且用户有权限访问
     */
    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null || Boolean.TRUE.equals(forward.getDeleteRequested())) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (currentUser.getRoleId() != ADMIN_ROLE_ID &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    /**
     * 获取所需的节点信息
     */
    private NodeInfo getRequiredNodes(Tunnel tunnel) {
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return NodeInfo.error("入口节点不存在");
        }

        Node outNode = null;
        List<Node> relayNodes = new ArrayList<>();
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            List<Long> relayNodeIds = tunnelService.getRelayNodeIds(tunnel);
            for (int i = 0; i < relayNodeIds.size(); i++) {
                Node relayNode = nodeService.getNodeById(relayNodeIds.get(i));
                if (relayNode == null) {
                    return NodeInfo.error(i == relayNodeIds.size() - 1
                            ? "出口节点不存在" : "第 " + (i + 1) + " 跳中转节点不存在");
                }
                relayNodes.add(relayNode);
            }
            outNode = relayNodes.isEmpty() ? null : relayNodes.get(relayNodes.size() - 1);
        }

        return NodeInfo.success(inNode, outNode, relayNodes);
    }

    /**
     * 检查用户权限和限制
     */
    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        // 检查用户隧道权限
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        // 流量限制检查
        if (userInfo.getFlow() <= 0) {
            return UserPermissionResult.error("用户总流量已用完");
        }
        if (userTunnel.getFlow() <= 0) {
            return UserPermissionResult.error("该隧道流量已用完");
        }

        // 转发数量限制检查
        R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    /**
     * 检查用户转发数量限制
     */
    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制
        long userForwardCount = this.count(new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("delete_requested", 0));
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId)
                .eq("delete_requested", 0);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    /**
     * 检查用户流量限制
     */
    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        // 数据库中的流量已按计费类型处理，直接使用总和
        long tunnelFlow = userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    /**
     * 创建Forward实体对象
     */
    private Forward createForwardEntity(
            ForwardDto forwardDto,
            UserInfo currentUser,
            PortReservationService.PortAllocation portAllocation) {
        Forward forward = new Forward();
        // 先复制DTO的属性，再设置其他属性，避免被覆盖
        BeanUtils.copyProperties(forwardDto, forward);
        forward.setStatus(FORWARD_STATUS_ACTIVE);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        forward.setSyncStatus(SYNC_STATUS_PENDING);
        forward.setSyncError(null);
        forward.setDeleteRequested(false);
        forward.setPortReservationToken(portAllocation.getToken());
        return forward;
    }

    /**
     * 更新Forward实体对象
     */
    private Forward updateForwardEntity(
            ForwardUpdateDto forwardUpdateDto,
            Forward existForward,
            PortReservationService.PortAllocation portAllocation) {
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardUpdateDto, forward);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setUserId(existForward.getUserId());
        forward.setUserName(existForward.getUserName());
        forward.setCreatedTime(existForward.getCreatedTime());
        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * 创建Gost服务
     */
    private R createGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        Map<Long, Integer> relayPorts = getRelayPorts(forward, tunnel);
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            R relayResult = createRelayServices(nodeInfo, serviceName, forward, tunnel, relayPorts);
            if (relayResult.getCode() != 0) {
                deleteRelayServices(nodeInfo.getRelayNodes(), serviceName);
                return relayResult;
            }
            R chainResult = createChainService(nodeInfo.getInNode(), serviceName,
                    buildRelayAddresses(nodeInfo.getRelayNodes(), relayPorts), tunnel.getProtocol(), tunnel.getInterfaceName());
            if (chainResult.getCode() != 0) {
                deleteRelayServices(nodeInfo.getRelayNodes(), serviceName);
                return chainResult;
            }
        }

        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }


        R serviceResult = createMainService(nodeInfo.getInNode(), serviceName, forward, limiter, tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            deleteRelayServices(nodeInfo.getRelayNodes(), serviceName);
            return serviceResult;
        }
        return R.ok();
    }

    /**
     * 更新Gost服务
     */
    private R updateGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            Map<Long, Integer> relayPorts = getRelayPorts(forward, tunnel);
            R relayResult = updateRelayServices(nodeInfo, serviceName, forward, tunnel, relayPorts);
            if (relayResult.getCode() != 0) {
                return relayResult;
            }
            R chainResult = updateChainService(nodeInfo.getInNode(), serviceName,
                    buildRelayAddresses(nodeInfo.getRelayNodes(), relayPorts), tunnel.getProtocol(), tunnel.getInterfaceName());
            if (chainResult.getCode() != 0) {
                return chainResult;
            }
        }
        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }
        // 更新主服务
        R serviceResult = updateMainService(nodeInfo.getInNode(), serviceName, forward, limiter, tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            return serviceResult;
        }

        return R.ok();
    }

    /**
     * 隧道变化时更新Gost服务：先删除原配置，再创建新配置
     */
    private R updateGostServicesWithTunnelChange(Forward existForward, Forward updatedForward, Tunnel newTunnel,
                                                 Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel,
                                                 Map<Long, Integer> relayPorts) {
        // 1. 获取原隧道信息
        Tunnel oldTunnel = tunnelService.getById(existForward.getTunnelId());
        if (oldTunnel == null) {
            return R.err("原隧道不存在，无法删除旧配置");
        }

        // 2. 删除原有的Gost服务配置
        R deleteResult = deleteOldGostServices(existForward, oldTunnel);
        if (deleteResult.getCode() != 0) {
            // 删除失败时记录日志，但不影响后续创建（可能原配置已不存在）
            log.info("删除原隧道{}的Gost配置失败: {}", oldTunnel.getId(), deleteResult.getMsg());
        }

        // 3. 创建新的Gost服务配置
        R createResult = createGostServices(updatedForward, newTunnel, limiter, nodeInfo, userTunnel, relayPorts);
        if (createResult.getCode() != 0) {
            existForward.setStatus(FORWARD_STATUS_ERROR);
            existForward.setUpdatedTime(System.currentTimeMillis());
            this.updateById(existForward);
            return R.err("创建新隧道配置失败: " + createResult.getMsg());
        }

        return R.ok();
    }

    private R createGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo,
                                 UserTunnel userTunnel, Map<Long, Integer> relayPorts) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            R relayResult = createRelayServices(nodeInfo, serviceName, forward, tunnel, relayPorts);
            if (relayResult.getCode() != 0) {
                deleteRelayServices(nodeInfo.getRelayNodes(), serviceName);
                return relayResult;
            }
            R chainResult = createChainService(nodeInfo.getInNode(), serviceName,
                    buildRelayAddresses(nodeInfo.getRelayNodes(), relayPorts), tunnel.getProtocol(), tunnel.getInterfaceName());
            if (chainResult.getCode() != 0) {
                deleteRelayServices(nodeInfo.getRelayNodes(), serviceName);
                return chainResult;
            }
        }
        String interfaceName = tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD ? null : forward.getInterfaceName();
        R serviceResult = createMainService(nodeInfo.getInNode(), serviceName, forward, limiter,
                tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            deleteRelayServices(nodeInfo.getRelayNodes(), serviceName);
        }
        return serviceResult;
    }

    /**
     * 删除原有的Gost服务（隧道变化时专用）
     */
    private R deleteOldGostServices(Forward forward, Tunnel oldTunnel) {
        // 获取原隧道的用户隧道关系
        UserTunnel oldUserTunnel = getUserTunnel(forward.getUserId(), oldTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), oldUserTunnel);

        // 获取原隧道的节点信息
        NodeInfo oldNodeInfo = getRequiredNodes(oldTunnel);

        // 删除主服务（使用原隧道的入口节点）
        if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
            GostDto serviceResult = GostUtil.DeleteService(oldNodeInfo.getInNode().getId(), serviceName);
            if (!isGostOperationSuccess(serviceResult)) {
                log.info("删除主服务失败: {}", serviceResult.getMsg());
            }
        }

        // 如果原隧道是隧道转发类型，需要删除链和远程服务
        if (oldTunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            // 删除链服务
            if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
                GostDto chainResult = GostUtil.DeleteChains(oldNodeInfo.getInNode().getId(), serviceName);
                if (!isGostOperationSuccess(chainResult)) {
                    log.info("删除链服务失败: {}", chainResult.getMsg());
                }
            }

            List<Long> relayNodeIds = tunnelService.getRelayNodeIds(oldTunnel);
            for (Long relayNodeId : relayNodeIds) {
                GostDto remoteResult = GostUtil.DeleteRemoteService(relayNodeId, serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    log.info("删除节点{}远程服务失败: {}", relayNodeId, remoteResult.getMsg());
                }
            }
        }

        return R.ok();
    }

    /**
     * 删除Gost服务
     */
    private R deleteGostServices(Forward forward, Tunnel tunnel, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        String firstError = null;

        // 删除主服务
        GostDto serviceResult = GostUtil.DeleteService(nodeInfo.getInNode().getId(), serviceName);
        if (!isGostOperationSuccess(serviceResult)) {
            firstError = serviceResult == null ? "入口节点无响应" : serviceResult.getMsg();
        }

        // 隧道转发需要删除链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            GostDto chainResult = GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            if (!isGostOperationSuccess(chainResult) && firstError == null) {
                firstError = chainResult == null ? "入口节点无响应" : chainResult.getMsg();
            }

            for (Node relayNode : nodeInfo.getRelayNodes()) {
                GostDto remoteResult = GostUtil.DeleteRemoteService(relayNode.getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult) && firstError == null) {
                    firstError = remoteResult == null ? relayNode.getName() + " 节点无响应" : remoteResult.getMsg();
                }
            }
        }

        return firstError == null ? R.ok() : R.err(firstError);
    }

    /**
     * 创建链服务
     */
    private R createChainService(Node inNode, String serviceName, List<String> remoteAddrs,
                                 String protocol, String interfaceName) {
        GostDto result = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddrs, protocol, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建远程服务
     */
    private R createRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        GostDto result = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    private R createRelayServices(NodeInfo nodeInfo, String serviceName, Forward forward, Tunnel tunnel,
                                  Map<Long, Integer> relayPorts) {
        List<Node> relayNodes = nodeInfo.getRelayNodes();
        for (int i = relayNodes.size() - 1; i >= 0; i--) {
            Node relayNode = relayNodes.get(i);
            Integer port = relayPorts.get(relayNode.getId());
            if (port == null) {
                return R.err("节点 " + relayNode.getName() + " 缺少中转端口");
            }
            String target = i == relayNodes.size() - 1
                    ? forward.getRemoteAddr()
                    : formatAddress(relayNodes.get(i + 1).getServerIp(), relayPorts.get(relayNodes.get(i + 1).getId()));
            String interfaceName = i == relayNodes.size() - 1 ? forward.getInterfaceName() : null;
            GostDto result = GostUtil.AddRemoteService(relayNode.getId(), serviceName, port, target,
                    tunnel.getProtocol(), forward.getStrategy(), interfaceName);
            if (!isGostOperationSuccess(result)) {
                return R.err("创建节点 " + relayNode.getName() + " 中转服务失败: " + result.getMsg());
            }
        }
        return R.ok();
    }

    private R updateRelayServices(NodeInfo nodeInfo, String serviceName, Forward forward, Tunnel tunnel,
                                  Map<Long, Integer> relayPorts) {
        List<Node> relayNodes = nodeInfo.getRelayNodes();
        for (int i = relayNodes.size() - 1; i >= 0; i--) {
            Node relayNode = relayNodes.get(i);
            Integer port = relayPorts.get(relayNode.getId());
            if (port == null) {
                return R.err("节点 " + relayNode.getName() + " 缺少中转端口");
            }
            String target = i == relayNodes.size() - 1
                    ? forward.getRemoteAddr()
                    : formatAddress(relayNodes.get(i + 1).getServerIp(), relayPorts.get(relayNodes.get(i + 1).getId()));
            String interfaceName = i == relayNodes.size() - 1 ? forward.getInterfaceName() : null;
            GostDto result = GostUtil.UpdateRemoteService(relayNode.getId(), serviceName, port, target,
                    tunnel.getProtocol(), forward.getStrategy(), interfaceName);
            if (result != null && result.getMsg() != null && result.getMsg().contains(GOST_NOT_FOUND_MSG)) {
                result = GostUtil.AddRemoteService(relayNode.getId(), serviceName, port, target,
                        tunnel.getProtocol(), forward.getStrategy(), interfaceName);
            }
            if (!isGostOperationSuccess(result)) {
                return R.err("更新节点 " + relayNode.getName() + " 中转服务失败: " +
                        (result == null ? "节点无响应" : result.getMsg()));
            }
        }
        return R.ok();
    }

    private void deleteRelayServices(List<Node> relayNodes, String serviceName) {
        for (Node relayNode : relayNodes) {
            GostUtil.DeleteRemoteService(relayNode.getId(), serviceName);
        }
    }

    private List<String> buildRelayAddresses(List<Node> relayNodes, Map<Long, Integer> relayPorts) {
        return relayNodes.stream()
                .map(node -> formatAddress(node.getServerIp(), relayPorts.get(node.getId())))
                .collect(Collectors.toList());
    }

    private String formatAddress(String host, Integer port) {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    /**
     * 创建主服务
     */
    private R createMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 更新链服务
     */
    private R updateChainService(Node inNode, String serviceName, List<String> remoteAddrs,
                                 String protocol, String interfaceName) {
        GostDto createResult = GostUtil.UpdateChains(inNode.getId(), serviceName, remoteAddrs, protocol, interfaceName);
        if (createResult != null && createResult.getMsg() != null && createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddrs, protocol, interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult == null ? "节点无响应" : createResult.getMsg());
    }

    /**
     * 更新远程服务
     */
    private R updateRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        // 创建新远程服务
        GostDto createResult = GostUtil.UpdateRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        if (createResult != null && createResult.getMsg() != null
                && createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        }
        return isGostOperationSuccess(createResult)
                ? R.ok()
                : R.err(createResult == null ? "节点无响应" : createResult.getMsg());
    }

    /**
     * 更新主服务
     */
    private R updateMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.UpdateService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);

        if (result != null && result.getMsg() != null
                && result.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        }

        return isGostOperationSuccess(result)
                ? R.ok()
                : R.err(result == null ? "节点无响应" : result.getMsg());
    }

    /**
     * 更新转发状态为错误
     */
    private void updateForwardStatusToError(Forward forward) {
        forward.setStatus(FORWARD_STATUS_ERROR);
        this.updateById(forward);
    }

    /**
     * 获取用户隧道关系
     */
    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    /**
     * 检查隧道是否发生变化
     */
    private boolean isTunnelChanged(Forward existForward, ForwardUpdateDto updateDto) {
        return !existForward.getTunnelId().equals(updateDto.getTunnelId());
    }

    /**
     * 检查Gost操作是否成功
     */
    private boolean isGostOperationSuccess(GostDto gostResult) {
        return gostResult != null && Objects.equals(gostResult.getMsg(), GOST_SUCCESS_MSG);
    }

    private Map<Long, Integer> getRelayPorts(Forward forward, Tunnel tunnel) {
        Map<Long, Integer> relayPorts = new LinkedHashMap<>(forwardHopPortService.getPortMap(forward.getId()));
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && tunnel.getOutNodeId() != null
                && forward.getOutPort() != null) {
            relayPorts.putIfAbsent(tunnel.getOutNodeId(), forward.getOutPort());
        }
        return relayPorts;
    }


    /**
     * 检查指定的入口端口是否可用（可排除指定的转发ID）
     */
    private ForwardPortAvailabilityDto getInPortAvailability(
            Tunnel tunnel, Integer port, Long excludeForwardId) {
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        Set<Integer> usedPorts = inNode == null
                ? Collections.emptySet()
                : portReservationService.listUsedPorts(
                        tunnel.getInNodeId(), tunnel.getProtocol(), excludeForwardId);
        return evaluateInPortAvailability(inNode, port, usedPorts);
    }

    static ForwardPortAvailabilityDto evaluateInPortAvailability(
            Node inNode, Integer port, Set<Integer> usedPorts) {
        if (inNode == null) {
            return new ForwardPortAvailabilityDto(
                    false, "入口节点不存在，无法校验端口", port, null, null);
        }

        Integer minPort = inNode.getPortSta();
        Integer maxPort = inNode.getPortEnd();
        if (minPort == null || maxPort == null) {
            return new ForwardPortAvailabilityDto(
                    false, "入口节点未配置可用端口范围", port, minPort, maxPort);
        }

        if (port == null || port < minPort || port > maxPort) {
            return new ForwardPortAvailabilityDto(
                    false,
                    "端口 " + port + " 不在入口节点允许范围 " + minPort + "-" + maxPort + " 内",
                    port,
                    minPort,
                    maxPort);
        }

        if (usedPorts.contains(port)) {
            return new ForwardPortAvailabilityDto(
                    false, "入口端口 " + port + " 已被占用，请更换端口", port, minPort, maxPort);
        }

        return new ForwardPortAvailabilityDto(
                true, "入口端口 " + port + " 可用", port, minPort, maxPort);
    }

    /**
     * 构建服务名称，优化后减少重复查询
     */
    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }

    static Integer resolveLimiterForUpdate(UserTunnel userTunnel, boolean administratorOwnsForward) {
        if (administratorOwnsForward || userTunnel == null) {
            return null;
        }
        return userTunnel.getSpeedId();
    }

    @Override
    public R reconcileForward(Long id, Long oldTunnelId, String oldServiceName) {
        Forward forward = this.getById(id);
        if (forward == null) {
            return R.ok();
        }

        if (oldTunnelId != null && oldServiceName != null) {
            Tunnel oldTunnel = tunnelService.getById(oldTunnelId);
            if (oldTunnel != null) {
                R deleteResult = deleteGostServicesByName(oldTunnel, oldServiceName);
                if (deleteResult.getCode() != 0) {
                    return deleteResult;
                }
            }
        }
        if (Boolean.TRUE.equals(forward.getDeleteRequested())) {
            return R.ok();
        }

        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        UserTunnel userTunnel =
                getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        User owner = userService.getById(forward.getUserId());
        Integer limiter = owner != null && owner.getRoleId() == ADMIN_ROLE_ID
                ? null
                : userTunnel == null ? null : userTunnel.getSpeedId();

        R updateResult =
                updateGostServices(forward, tunnel, limiter, nodeInfo, userTunnel);
        if (updateResult.getCode() != 0) {
            return updateResult;
        }
        R statusResult = applyDesiredStatus(forward, nodeInfo, userTunnel);
        if (statusResult.getCode() != 0) {
            return statusResult;
        }

        Forward latest = this.getById(id);
        if (latest != null
                && !Boolean.TRUE.equals(latest.getDeleteRequested())
                && Objects.equals(
                        latest.getPortReservationToken(),
                        forward.getPortReservationToken())) {
            portReservationService.releaseObsolete(
                    id, forward.getPortReservationToken());
        }
        return R.ok();
    }

    @Override
    public R reconcileForwardDeletion(
            Long id, Long tunnelId, String serviceName) {
        Forward forward = this.getById(id);
        if (forward == null) {
            return R.ok();
        }
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel == null) {
            return R.err("隧道不存在，无法删除节点配置");
        }
        R deleteResult = deleteGostServicesByName(tunnel, serviceName);
        if (deleteResult.getCode() != 0) {
            return deleteResult;
        }
        portReservationService.releaseForward(id);
        forwardHopPortService.removeByForwardId(id);
        return this.removeById(id) ? R.ok() : R.err("删除转发记录失败");
    }

    private R applyDesiredStatus(
            Forward forward, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName =
                buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        boolean active = forward.getStatus() == FORWARD_STATUS_ACTIVE;
        GostDto entryResult = active
                ? GostUtil.ResumeService(nodeInfo.getInNode().getId(), serviceName)
                : GostUtil.PauseService(nodeInfo.getInNode().getId(), serviceName);
        if (!isGostOperationSuccess(entryResult)) {
            return R.err(entryResult == null ? "入口节点无响应" : entryResult.getMsg());
        }

        for (Node relayNode : nodeInfo.getRelayNodes()) {
            GostDto relayResult = active
                    ? GostUtil.ResumeRemoteService(relayNode.getId(), serviceName)
                    : GostUtil.PauseRemoteService(relayNode.getId(), serviceName);
            if (!isGostOperationSuccess(relayResult)) {
                return R.err(relayResult == null
                        ? relayNode.getName() + " 节点无响应"
                        : relayResult.getMsg());
            }
        }
        return R.ok();
    }

    private R deleteGostServicesByName(Tunnel tunnel, String serviceName) {
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        String firstError = null;
        GostDto serviceResult =
                GostUtil.DeleteService(nodeInfo.getInNode().getId(), serviceName);
        if (!isGostDeleteSuccess(serviceResult)) {
            firstError =
                    serviceResult == null ? "入口节点无响应" : serviceResult.getMsg();
        }

        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            GostDto chainResult =
                    GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            if (!isGostDeleteSuccess(chainResult) && firstError == null) {
                firstError =
                        chainResult == null ? "入口节点无响应" : chainResult.getMsg();
            }
            for (Node relayNode : nodeInfo.getRelayNodes()) {
                GostDto relayResult =
                        GostUtil.DeleteRemoteService(relayNode.getId(), serviceName);
                if (!isGostDeleteSuccess(relayResult) && firstError == null) {
                    firstError = relayResult == null
                            ? relayNode.getName() + " 节点无响应"
                            : relayResult.getMsg();
                }
            }
        }
        return firstError == null ? R.ok() : R.err(firstError);
    }

    private boolean isGostDeleteSuccess(GostDto result) {
        return isGostOperationSuccess(result)
                || result != null
                && result.getMsg() != null
                && result.getMsg().contains(GOST_NOT_FOUND_MSG);
    }

    @Override
    @Transactional
    public void updateForwardA(Forward forward) {
        if (forward == null
                || forward.getId() == null
                || Boolean.TRUE.equals(forward.getDeleteRequested())) {
            return;
        }
        forwardSyncOutboxService.enqueueUpsert(forward.getId(), null, null);
    }


    // ========== 内部数据类 ==========

    /**
     * 用户信息封装类
     */
    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;
    }

    /**
     * 用户权限检查结果
     */
    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    /**
     * 节点信息封装类
     */
    @Data
    private static class NodeInfo {
        private final boolean hasError;
        private final String errorMessage;
        private final Node inNode;
        private final Node outNode;
        private final List<Node> relayNodes;

        private NodeInfo(boolean hasError, String errorMessage, Node inNode, Node outNode, List<Node> relayNodes) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inNode = inNode;
            this.outNode = outNode;
            this.relayNodes = relayNodes;
        }

        public static NodeInfo success(Node inNode, Node outNode, List<Node> relayNodes) {
            return new NodeInfo(false, null, inNode, outNode, relayNodes);
        }

        public static NodeInfo error(String errorMessage) {
            return new NodeInfo(true, errorMessage, null, null, Collections.emptyList());
        }
    }

    /**
     * 诊断结果数据类
     */
    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;
    }
}
