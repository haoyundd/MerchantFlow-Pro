package com.hmdp.aspect;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class AkSkAuthAspect {

    @Value("${hmdp.aksk.access-key}")
    private String accessKey;

    @Value("${hmdp.aksk.secret-key}")
    private String secretKey;

    @Value("${hmdp.aksk.nonce-ttl-seconds}")
    private long nonceTtlSeconds;

    @Value("${hmdp.aksk.timestamp-tolerance-seconds}")
    private long timestampToleranceSeconds;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(com.hmdp.annotation.AkSkAuth)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 后台写接口必须通过 AK/SK 签名认证，失败时直接返回 401。
        HttpServletRequest request = currentRequest();
        HttpServletResponse response = currentResponse();
        String error = validate(request);
        if (error != null) {
            if (response != null) {
                response.setStatus(401);
            }
            return Result.fail(error);
        }
        return joinPoint.proceed();
    }

    private String validate(HttpServletRequest request) {
        if (request == null) {
            return "非法请求";
        }
        String ak = request.getHeader("X-AK");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String signature = request.getHeader("X-Signature");
        if (StrUtil.hasBlank(ak, timestamp, nonce, signature)) {
            return "缺少 AK/SK 签名请求头";
        }
        if (!accessKey.equals(ak)) {
            return "AK 不存在";
        }
        if (!isTimestampValid(timestamp)) {
            return "请求时间戳已过期";
        }
        String nonceKey = "aksk:nonce:" + ak + ":" + nonce;
        Boolean firstSeen = stringRedisTemplate.opsForValue()
                .setIfAbsent(nonceKey, "1", nonceTtlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(firstSeen)) {
            return "请求 nonce 已使用";
        }

        String raw = ak + "\n" + timestamp + "\n" + nonce + "\n"
                + request.getMethod() + "\n" + request.getRequestURI();
        String expected = hmacSha256Hex(secretKey, raw);
        if (!expected.equalsIgnoreCase(signature)) {
            return "签名错误";
        }
        return null;
    }

    private boolean isTimestampValid(String timestamp) {
        try {
            long requestSeconds = Long.parseLong(timestamp);
            long nowSeconds = Instant.now().getEpochSecond();
            return Math.abs(nowSeconds - requestSeconds) <= timestampToleranceSeconds;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String hmacSha256Hex(String secret, String raw) {
        try {
            // 使用标准 HMAC-SHA256，避免明文 SK 出现在网络中。
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("生成签名失败", e);
        }
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    private HttpServletResponse currentResponse() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getResponse();
    }
}
