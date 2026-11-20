package com.wherelee.chatry.bootstrap;

import com.wherelee.base.modules.platform.common.PasswordGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 垂直应用种子数据：管理员 + 演示用户（可选）。
 *
 * <p>内核只提供身份能力，不定义业务账号；这里按产品需要创建两类账号。
 * 账号已存在时不会重置密码。</p>
 */
@Component
@Profile("!integration")
public class ChatryDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatryDataInitializer.class);

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ChatrySeedProperties seed;

    public ChatryDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                 ChatrySeedProperties seed) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String adminPassword = seed.adminPassword();
        boolean generated = adminPassword == null || adminPassword.isBlank();
        String password = generated ? PasswordGenerator.generate() : adminPassword;
        if (ensureUser("admin", password, "管理员", ADMIN_ROLE, false) && generated) {
            log.warn("已创建管理员 admin，随机初始密码={}（仅本次启动打印；演示环境已关闭强制改密）", password);
        }

        if (seed.demoUserEnabled()) {
            boolean created = ensureUser(seed.demoUsername(), seed.demoPassword(), "演示用户", USER_ROLE, false);
            if (created) {
                log.info("已创建演示用户 {}（密码见 chatry.seed.demo-password 配置）", seed.demoUsername());
            }
        }
    }

    private boolean ensureUser(String username, String password, String nickname,
                               String role, boolean mustChangePassword) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE tenant_id = 0 AND username = ?", Integer.class, username);
        if (existing != null && existing > 0) {
            log.info("账号 {} 已存在，跳过种子", username);
            return false;
        }
        jdbc.update("""
                INSERT INTO t_user
                    (tenant_id, username, password_hash, nickname, role, status,
                     must_change_password, token_version)
                VALUES (0, ?, ?, ?, ?, 'ACTIVE', ?, 0)
                """, username, passwordEncoder.encode(password), nickname, role, mustChangePassword);
        jdbc.update("""
                INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id)
                SELECT 0, u.id, r.id
                FROM t_user u
                         JOIN t_role r ON r.tenant_id = 0 AND r.code = ?
                WHERE u.tenant_id = 0 AND u.username = ?
                """, role, username);
        return true;
    }
}
