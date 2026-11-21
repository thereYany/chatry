package com.wherelee.base.modules.identity.auth.dto;

public record LoginResponse(String token, UserInfo user, boolean mustChangePassword) {
}