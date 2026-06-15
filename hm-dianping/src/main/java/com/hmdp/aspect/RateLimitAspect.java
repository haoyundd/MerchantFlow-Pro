package com.hmdp.aspect;

import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitType;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;

@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    public void init() {
        // Lua 保证 INCR 和 EXPIRE 原子执行，避免并发下计数和过期时间错乱。
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setResultType(Long.class);
        rateLimitScript.setScriptText(
                "local current = redis.call('incr', KEYS[1]);" +
                        "if current == 1 then redis.call('expire', KEYS[1], ARGV[1]); end;" +
                        "if current > tonumber(ARGV[2]) then return 0 else return 1 end;"
        );
    }

    @Around("@annotation(com.hmdp.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 根据注解配置构建限流 key，然后用 Redis Lua 做窗口计数。
        RateLimit rateLimit = ((MethodSignature) joinPoint.getSignature())
                .getMethod().getAnnotation(RateLimit.class);
        HttpServletRequest request = currentRequest();
        String key = buildRateLimitKey(joinPoint, rateLimit, request);
        Long allowed = stringRedisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(rateLimit.windowSeconds()),
                String.valueOf(rateLimit.max())
        );
        if (allowed == null || allowed == 0) {
            HttpServletResponse response = currentResponse();
            if (response != null) {
                response.setStatus(429);
            }
            return Result.fail("请求过于频繁，请稍后再试");
        }
        return joinPoint.proceed();
    }

    private String buildRateLimitKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit, HttpServletRequest request) {
        // key 中包含方法签名，避免不同接口互相影响。
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodKey = signature.getDeclaringTypeName() + "." + signature.getName();
        if (rateLimit.type() == RateLimitType.GLOBAL) {
            return "rate:global:" + methodKey;
        }
        if (rateLimit.type() == RateLimitType.USER) {
            UserDTO user = UserHolder.getUser();
            if (user != null && user.getId() != null) {
                return "rate:user:" + methodKey + ":" + user.getId();
            }
        }
        return "rate:ip:" + methodKey + ":" + clientIp(request);
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    private HttpServletResponse currentResponse() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getResponse();
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
