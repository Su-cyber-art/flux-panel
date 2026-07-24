package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowBatchDto;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.*;
import com.admin.service.FlowAccountingService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 新节点使用带进程实例和单调序列号的绝对计数批次。服务端在一个数据库
 * 事务内完成去重、增量计算和三张业务表记账；旧节点的单条增量接口继续兼容。
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    FlowAccountingService flowAccountingService;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret) {
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @PostMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody String rawData, String secret) {
        Node node = findNodeBySecret(secret);
        if (node == null) {
            return SUCCESS_RESPONSE;
        }

        String decryptedData = decryptIfNeeded(rawData, secret);
        FlowDto flowData = JSONObject.parseObject(decryptedData, FlowDto.class);
        if (flowData == null || Objects.equals(flowData.getN(), "web_api")) {
            return SUCCESS_RESPONSE;
        }

        FlowAccountingService.AccountingResult result =
                flowAccountingService.accountLegacy(node, flowData);
        enforceLimits(result);
        return SUCCESS_RESPONSE;
    }

    @PostMapping("/upload/batch")
    public String uploadFlowBatch(@RequestBody String rawData, String secret) {
        Node node = findNodeBySecret(secret);
        if (node == null) {
            return SUCCESS_RESPONSE;
        }

        String decryptedData = decryptIfNeeded(rawData, secret);
        FlowBatchDto batch = JSONObject.parseObject(decryptedData, FlowBatchDto.class);
        FlowAccountingService.AccountingResult result =
                flowAccountingService.accountBatch(node, batch);
        enforceLimits(result);
        log.debug("节点 {} 流量批次 {} 处理完成，重复={}",
                node.getId(), batch.getSequence(), result.isDuplicate());
        return SUCCESS_RESPONSE;
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    private void enforceLimits(FlowAccountingService.AccountingResult result) {
        for (Integer userId : result.getUserIds()) {
            checkUserRelatedLimits(userId.toString());
        }
        for (Integer userTunnelId : result.getUserTunnelIds()) {
            UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
            if (userTunnel != null) {
                checkUserTunnelRelatedLimits(
                        userTunnelId.toString(), userTunnel.getUserId().toString());
            }
        }
    }

    private void checkUserRelatedLimits(String userId) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(userId);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId);
        }
    }

    public void pauseAllUserServices(String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("delete_requested", 0));
        pauseService(forwardList);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String userId) {

        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) return;
        long flow = userTunnel.getInFlow() + userTunnel.getOutFlow();
        if (flow >= userTunnel.getFlow() *  BYTES_TO_GB) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId);
            return;
        }

        if (userTunnel.getStatus() != 1) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId);
        }


    }

    private void pauseSpecificForward(Integer tunnelId, String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>()
                .eq("tunnel_id", tunnelId)
                .eq("user_id", userId)
                .eq("delete_requested", 0));
        pauseService(forwardList);
    }

    public void pauseService(List<Forward> forwardList) {
        for (Forward forward : forwardList) {
            forwardService.requestForwardStatus(forward.getId(), 0);
        }
    }

    private Node findNodeBySecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        return nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
    }
}
