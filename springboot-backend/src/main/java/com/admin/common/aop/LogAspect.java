package com.admin.common.aop;

import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.IpUtils;
import com.admin.common.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Component
@Aspect
@Slf4j
public class LogAspect {

    @Pointcut("@annotation(com.admin.common.aop.LogAnnotation)")
    public void pt() {
    }

    @AfterReturning(value = "pt()")
    public void log(JoinPoint joinPoint) {
        RequestMetadata metadata = requestMetadata(joinPoint);
        log.info(
                "【请求日志】用户ID:[{}], IP地址:[{}], 请求方式:[{}], 控制器方法:[{}], 结果:[成功]",
                metadata.userId,
                metadata.ipAddress,
                metadata.requestMethod,
                metadata.controllerMethod);
    }

    @AfterThrowing(value = "pt()", throwing = "exception")
    public void recordLog(JoinPoint joinPoint, Exception exception) {
        try {
            RequestMetadata metadata = requestMetadata(joinPoint);
            log.warn(
                    "【异常日志】用户ID:[{}], IP地址:[{}], 请求方式:[{}], 控制器方法:[{}], 异常类型:[{}]",
                    metadata.userId,
                    metadata.ipAddress,
                    metadata.requestMethod,
                    metadata.controllerMethod,
                    exception == null ? "未知异常" : exception.getClass().getSimpleName());
        } catch (Exception loggingException) {
            log.warn("记录异常日志失败: {}", loggingException.getClass().getSimpleName());
        }
    }

    private RequestMetadata requestMetadata(JoinPoint joinPoint) {
        HttpServletRequest request = HttpContextUtils.getHttpServletRequest();
        String authorization = request.getHeader("Authorization");
        Object userId = "未登录";
        if (authorization != null && !authorization.isBlank()) {
            try {
                userId = JwtUtil.getUserIdFromToken(authorization);
            } catch (Exception ignored) {
                userId = "无效令牌";
            }
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String controllerMethod = joinPoint.getTarget().getClass().getName()
                + "." + signature.getName();
        return new RequestMetadata(
                userId,
                IpUtils.getIpAddr(request),
                request.getMethod(),
                controllerMethod);
    }

    private record RequestMetadata(
            Object userId,
            String ipAddress,
            String requestMethod,
            String controllerMethod) {
    }
}
