package com.hmdp.annotation;

public enum RateLimitType {
    /**
     * 全局维度：同一个接口共用一个限流桶。
     */
    GLOBAL,
    /**
     * IP 维度：同一个接口按客户端 IP 分桶。
     */
    IP,
    /**
     * 用户维度：登录用户按用户 id 分桶，未登录时退化为 IP。
     */
    USER
}
