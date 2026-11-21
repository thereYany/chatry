package com.wherelee.chatry.mall;

import java.time.Instant;

/** 商城 SKU（额度包 / 秒杀品）。 */
public record MallSku(
        long id,
        long tenantId,
        String code,
        String title,
        String description,
        long priceCents,
        long creditCents,
        int stock,
        int perUserLimit,
        boolean flashSale,
        Long flashPriceCents,
        Instant flashStartAt,
        Instant flashEndAt,
        String status,
        int sort
) {
    public boolean unlimitedStock() {
        return stock < 0;
    }

    public boolean flashActive(Instant now) {
        return flashSale
                && "ON_SALE".equals(status)
                && flashStartAt != null && flashEndAt != null
                && !now.isBefore(flashStartAt) && now.isBefore(flashEndAt)
                && (unlimitedStock() || stock > 0);
    }

    /** 当前生效价格（分）：秒杀窗口内用秒杀价，否则原价。 */
    public long effectivePriceCents(Instant now) {
        return flashActive(now) && flashPriceCents != null ? flashPriceCents : priceCents;
    }
}
