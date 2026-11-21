package com.wherelee.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 新项目启动类（示例）。
 *
 * <p>复用 atry 内核的登录、权限、支付、存储与可观测性能力；
 * 自己的业务代码放在 {@code com.wherelee.template} 下，与内核隔离。</p>
 */
@SpringBootApplication(scanBasePackages = {"com.wherelee.base", "com.wherelee.template"})
public class TemplateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateApplication.class, args);
    }
}
