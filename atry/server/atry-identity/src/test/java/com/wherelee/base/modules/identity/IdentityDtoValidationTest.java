package com.wherelee.base.modules.identity;

import com.wherelee.base.modules.identity.auth.dto.ChangePasswordRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginRequest;
import com.wherelee.base.modules.identity.invitation.InvitationProperties;
import com.wherelee.base.modules.identity.invitation.dto.UserInvitationDtos;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 身份模块请求 DTO 校验与邀请配置夹紧测试。
 *
 * <p>不变式：非法用户名/角色码/弱密码在进入业务前被拦截；邀请有效期与限流窗口有安全上下界。</p>
 */
class IdentityDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void 登录请求必填() {
        assertThat(validator.validate(new LoginRequest("", ""))).hasSize(2);
        assertThat(validator.validate(new LoginRequest("alice", "pwd"))).isEmpty();
    }

    @Test
    void 修改密码请求要求强密码() {
        Set<ConstraintViolation<ChangePasswordRequest>> weak =
                validator.validate(new ChangePasswordRequest("old", "12345678"));
        assertThat(weak).isNotEmpty();

        assertThat(validator.validate(new ChangePasswordRequest("OldPass123", "NewPass123"))).isEmpty();
    }

    @Test
    void 邀请创建请求校验用户名角色码与昵称长度() {
        assertThat(validator.validate(new UserInvitationDtos.CreateRequest("ab", "n", "USER")))
                .isNotEmpty();                                    // 用户名太短
        assertThat(validator.validate(new UserInvitationDtos.CreateRequest(
                "validUser", "n", "lowercase")))
                .isNotEmpty();                                    // 角色码必须大写开头
        assertThat(validator.validate(new UserInvitationDtos.CreateRequest(
                "validUser", "x".repeat(21), "USER")))
                .isNotEmpty();                                    // 昵称超长
        assertThat(validator.validate(new UserInvitationDtos.CreateRequest(
                "validUser", "昵称", "USER"))).isEmpty();
    }

    @Test
    void 接受邀请请求要求强密码与确认密码() {
        assertThat(validator.validate(new UserInvitationDtos.AcceptRequest(
                "token", "weak", "weak"))).isNotEmpty();
        assertThat(validator.validate(new UserInvitationDtos.AcceptRequest(
                "", "GoodPass123", "GoodPass123"))).isNotEmpty();
        assertThat(validator.validate(new UserInvitationDtos.AcceptRequest(
                "token", "GoodPass123", "GoodPass123"))).isEmpty();
    }

    @Test
    void 邀请配置有安全上下界() {
        InvitationProperties properties = new InvitationProperties(
                "http://host/", 0, 0, 0);
        assertThat(properties.safeExpiryHours()).isEqualTo(1);
        assertThat(properties.safeMaxAttempts()).isEqualTo(1);
        assertThat(properties.safeWindowMinutes()).isEqualTo(1);
        assertThat(properties.normalizedBaseUrl()).isEqualTo("http://host");

        InvitationProperties tooLong = new InvitationProperties("http://host", 100000, 20, 10);
        assertThat(tooLong.safeExpiryHours()).isEqualTo(168);

        InvitationProperties blank = new InvitationProperties("  ", 72, 20, 10);
        assertThatThrownBy(blank::normalizedBaseUrl).isInstanceOf(IllegalStateException.class);
    }
}
