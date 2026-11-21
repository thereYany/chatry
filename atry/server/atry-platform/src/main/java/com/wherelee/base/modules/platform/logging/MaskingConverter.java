package com.wherelee.base.modules.platform.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * logback 转换词 {@code %maskedMsg}：先脱敏再输出，避免在 pattern 里拼原始消息。
 *
 * <p>用法见 {@code logback-spring.xml}：注册 conversionRule 后把 {@code %msg} 换成 {@code %maskedMsg}。</p>
 */
public class MaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(event.getFormattedMessage());
    }
}
