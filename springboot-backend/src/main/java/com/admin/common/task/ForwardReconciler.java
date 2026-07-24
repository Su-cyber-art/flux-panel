package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.service.ForwardService;
import com.admin.service.ForwardSyncOutboxService;
import com.admin.service.ForwardSyncOutboxService.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForwardReconciler {

    private static final int BATCH_SIZE = 20;

    private final ForwardSyncOutboxService outboxService;
    private final ForwardService forwardService;

    @Scheduled(
            fixedDelayString = "${forward.reconciler.delay-ms:2000}",
            initialDelayString = "${forward.reconciler.initial-delay-ms:3000}")
    public void reconcile() {
        for (SyncTask task : outboxService.findDueTasks(BATCH_SIZE)) {
            if (!outboxService.claim(task.getId())) {
                continue;
            }
            try {
                R result;
                if (ForwardSyncOutboxService.OPERATION_DELETE.equals(task.getOperation())) {
                    result = forwardService.reconcileForwardDeletion(
                            task.getForwardId(),
                            task.getOldTunnelId(),
                            task.getOldServiceName());
                } else {
                    result = forwardService.reconcileForward(
                            task.getForwardId(),
                            task.getOldTunnelId(),
                            task.getOldServiceName());
                }

                if (result != null && result.getCode() == 0) {
                    outboxService.complete(task);
                } else {
                    String error = result == null ? "节点无响应" : result.getMsg();
                    outboxService.fail(task, error);
                }
            } catch (Exception exception) {
                log.warn("转发 {} 状态收敛失败", task.getForwardId(), exception);
                outboxService.fail(task, exception.getMessage());
            }
        }
    }
}
