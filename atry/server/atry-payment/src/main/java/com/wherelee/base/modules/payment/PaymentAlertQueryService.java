package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.contracts.payment.PaymentAlertQueries;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaymentAlertQueryService implements PaymentAlertQueries {

    private final PaymentRepository paymentRepository;
    private final PaymentProperties paymentProperties;

    public PaymentAlertQueryService(PaymentRepository paymentRepository,
                                    PaymentProperties paymentProperties) {
        this.paymentRepository = paymentRepository;
        this.paymentProperties = paymentProperties;
    }

    @Override
    public long countSettlementUndeliveredBefore(LocalDateTime before) {
        return paymentRepository.countSettlementUndeliveredBefore(before);
    }

    @Override
    public long countPayingPaymentBefore(LocalDateTime before) {
        return paymentRepository.countPayingPaymentBefore(before);
    }

    @Override
    public long countRefundRetryExhausted() {
        return paymentRepository.countRefundRetryExhausted(paymentProperties.safeRefundMaxAttempts());
    }
}
