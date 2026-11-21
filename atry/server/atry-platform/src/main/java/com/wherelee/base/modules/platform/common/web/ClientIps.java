package com.wherelee.base.modules.platform.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** 客户端 IP 解析：只有在信任反向代理时才读取转发头，避免被客户端伪造。 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedHeaders) {
        if (request == null) {
            return "unknown";
        }
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
