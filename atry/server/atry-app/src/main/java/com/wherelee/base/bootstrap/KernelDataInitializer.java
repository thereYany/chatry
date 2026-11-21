package com.wherelee.base.bootstrap;

import com.wherelee.base.modules.platform.common.PasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内核种子数据：只创建一个管理员账号并绑定内置 ADMIN 角色。
 *
 * <p>不带任何垂直业务数据——演示账号与业务种子由垂直应用自己的 bootstrap 负责。</p>
 */
@Slf4j
@Component
@Profile("!integration")
@RequiredArgsConstructor
public class KernelDataInitializer implements ApplicationRunner {

    private static final String ADMIN_ROLE = "ADMIN";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final KernelSeedProperties seed;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE tenant_id = 0 AND username = ?", Integer.class, "admin");
        if (existing != null && existing > 0) {
            log.info("内核管理员账号已存在，跳过种子数据");
            return;
        }
        boolean generated = seed.adminPassword() == null || seed.adminPassword().isBlank();
        String password = generated ? PasswordGenerator.generate() : seed.adminPassword();
        jdbc.update("""
                INSERT INTO t_user
                    (tenant_id, username, password_hash, nickname, role, status,
                     must_change_password, token_version)
                VALUES (0, 'admin', ?, '管理员', ?, 'ACTIVE', TRUE, 0)
                """, passwordEncoder.encode(password), ADMIN_ROLE);
        jdbc.update("""
                INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id)
                SELECT 0, u.id, r.id
                FROM t_user u
                         JOIN t_role r ON r.tenant_id = 0 AND r.code = ?
                WHERE u.tenant_id = 0 AND u.username = 'admin'
                """, ADMIN_ROLE);
        if (generated) {
            log.warn("初始化管理员账号 admin 随机初始密码={}（仅本次启动打印，首次登录必须改密）", password);
        } else {
            log.info("创建管理员账号 admin，初始密码来自配置，首次登录必须改密");
        }
    }
}
