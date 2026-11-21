package com.wherelee.base.modules.identity;

import com.wherelee.base.modules.identity.invitation.InvitationProperties;
import com.wherelee.base.modules.identity.auth.AuthProperties;
import com.wherelee.base.modules.identity.security.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 身份模块的自装配入口。
 *
 * <p>模块自带 Mapper 扫描与配置属性注册，应用只依赖 jar 并扫描本包即可，
 * 不需要在每个宿主里重复声明 {@code @MapperScan}/{@code @ConfigurationPropertiesScan}。</p>
 */
@Configuration(proxyBeanMethods = false)
@MapperScan({"com.wherelee.base.modules.identity.invitation",
        "com.wherelee.base.modules.identity.user"})
@EnableConfigurationProperties({JwtProperties.class, InvitationProperties.class, AuthProperties.class})
public class IdentityConfig {
}
