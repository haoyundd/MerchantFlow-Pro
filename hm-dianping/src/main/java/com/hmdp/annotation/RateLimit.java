package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 时间窗口内允许的最大请求数。
     */
    int max() default 10;

    /**
     * 限流时间窗口，单位秒。
     */
    int windowSeconds() default 60;

    /**
     * 限流维度：全局、IP 或用户。
     */
    RateLimitType type() default RateLimitType.IP;
}
