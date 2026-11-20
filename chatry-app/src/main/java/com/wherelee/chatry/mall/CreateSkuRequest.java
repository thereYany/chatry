package com.wherelee.chatry.mall;

import java.time.Instant;

/** 管理员建品/秒杀配置请求（字段可空，服务层统一校验与默认值）。 */
public record CreateSkuRequest(String code, String title, String description,
                               Long priceCents, Long creditCents,
                               Integer stock, Integer perUserLimit,
                               Boolean flashSale, Long flashPriceCents,
                               Instant flashStartAt, Instant flashEndAt,
                               Integer sort) {
}
