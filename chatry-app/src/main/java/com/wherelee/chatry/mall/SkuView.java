package com.wherelee.chatry.mall;

import java.time.Instant;

/**
 * SKU 对外视图。
 *
 * @param priceCents        原价（分）
 * @param flashPriceCents   秒杀价（分，非秒杀为 null）
 * @param effectivePriceCents 当前生效价（秒杀窗口内为秒杀价）
 * @param flashActive       秒杀是否正在进行
 * @param stock             剩余库存（-1 表示不限量）
 */
public record SkuView(long id, String code, String title, String description,
                      long priceCents, Long flashPriceCents, long effectivePriceCents,
                      long creditCents, int stock, int perUserLimit,
                      boolean flashSale, boolean flashActive,
                      Instant flashStartAt, Instant flashEndAt,
                      String status) {

    public static SkuView from(MallSku sku, Instant now) {
        return new SkuView(sku.id(), sku.code(), sku.title(), sku.description(),
                sku.priceCents(), sku.flashPriceCents(), sku.effectivePriceCents(now),
                sku.creditCents(), sku.stock(), sku.perUserLimit(),
                sku.flashSale(), sku.flashActive(now),
                sku.flashStartAt(), sku.flashEndAt(), sku.status());
    }
}
