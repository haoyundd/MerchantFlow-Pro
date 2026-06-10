package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ThreadLocal 中没有用户信息，说明当前请求没有有效登录态，直接返回 401 并停止后续控制器执行
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }

        // 已登录用户放行，后续控制器可以从 UserHolder 中读取当前用户
        return true;
    }
}
