package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器：只拦截需要登录的接口，首页、商户、博客热门列表等公开接口放行
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        // 内部运维接口使用独立 AK/SK + nonce 认证，不能再要求普通用户 Token；
                        // 下一步仍会进入 Controller 上的 @AkSkAuth 和全局限流切面。
                        "/internal/ops/**",
                        // Lab Controller 自带独立实验令牌，且 Bean 只在 lab Profile 注册；
                        // 基础环境中该路径没有 Controller，直接返回 404。
                        "/internal/lab/**"
                )
                .order(1);

        // token 刷新拦截器：所有请求都尝试解析 token，有 token 时刷新登录态
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
    }
}
