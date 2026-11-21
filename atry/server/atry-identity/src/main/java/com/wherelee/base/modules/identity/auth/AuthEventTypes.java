package com.wherelee.base.modules.identity.auth;

/** 认证事件类型（审计用；落库字符串保持稳定）。 */
public final class AuthEventTypes {

    public static final String REGISTER = "REGISTER";
    public static final String REGISTER_THROTTLED = "REGISTER_THROTTLED";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";

    private AuthEventTypes() {
    }
}
