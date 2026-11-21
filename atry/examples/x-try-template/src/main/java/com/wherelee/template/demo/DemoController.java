package com.wherelee.template.demo;

import com.wherelee.base.modules.platform.aop.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 示例垂直业务：只需新增自己的包与接口，鉴权复用内核的权限码机制。 */
@RestController
public class DemoController {

    @GetMapping("/api/demo/ping")
    @RequirePermission("admin:user:manage")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "app", "atry-template");
    }
}
