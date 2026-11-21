package com.wherelee.chatry.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FundLedgerServiceTest {

    @Mock
    private FundLedgerRepository repository;

    private FundLedgerService service;

    @BeforeEach
    void setUp() {
        service = new FundLedgerService(repository);
        lenient().when(repository.ensureAccount(anyLong(), anyString(), anyLong(), anyString(),
                anyString(), anyString())).thenReturn(1L);
        lenient().when(repository.insertTransaction(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class))).thenReturn(100L);
    }

    @Test
    void topUpPostsClearingBonusAndLiability() {
        when(repository.transactionExists(0L, "topup:M1")).thenReturn(false);

        assertThat(service.postTopUp(0L, 9L, 999L, 1200L, "M1")).isTrue();

        ArgumentCaptor<BigDecimal> amounts = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository, org.mockito.Mockito.times(3)).insertEntry(eq(100L), anyLong(),
                org.mockito.ArgumentMatchers.anyString(), amounts.capture(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(amounts.getAllValues())
                .usingElementComparator(java.util.Comparator.comparing(java.math.BigDecimal::stripTrailingZeros))
                .containsExactlyInAnyOrder(new BigDecimal("9.99"), new BigDecimal("2.01"),
                        new BigDecimal("12.00"));
    }

    @Test
    void topUpIsIdempotentByEventKey() {
        when(repository.transactionExists(0L, "topup:M1")).thenReturn(true);

        assertThat(service.postTopUp(0L, 9L, 999L, 1200L, "M1")).isFalse();
        verify(repository, never()).insertTransaction(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void topUpWithoutBonusBalances() {
        when(repository.transactionExists(0L, "topup:M2")).thenReturn(false);

        service.postTopUp(0L, 9L, 3000L, 3000L, "M2");

        verify(repository, org.mockito.Mockito.times(2)).insertEntry(eq(100L), anyLong(),
                org.mockito.ArgumentMatchers.anyString(), any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void creditCheaperThanPaymentBooksRevenueForDifference() {
        when(repository.transactionExists(0L, "topup:M3")).thenReturn(false);

        service.postTopUp(0L, 9L, 5000L, 4000L, "M3");

        verify(repository, org.mockito.Mockito.times(3)).insertEntry(eq(100L), anyLong(),
                org.mockito.ArgumentMatchers.anyString(), any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void usagePostsLiabilityDebitAndRevenue() {
        when(repository.transactionExists(0L, "usage:settle:llm:R1")).thenReturn(false);

        assertThat(service.postUsage(0L, 9L, new BigDecimal("0.003794"),
                "settle:llm:R1", "CNY")).isTrue();

        verify(repository).insertEntry(eq(100L), anyLong(), eq("DEBIT"),
                eq(new BigDecimal("0.003794")), eq("CNY"));
        verify(repository).insertEntry(eq(100L), anyLong(), eq("CREDIT"),
                eq(new BigDecimal("0.003794")), eq("CNY"));
    }

    @Test
    void zeroUsageIsSkipped() {
        assertThat(service.postUsage(0L, 9L, BigDecimal.ZERO, "settle:llm:R2", "CNY")).isFalse();
        verify(repository, never()).insertTransaction(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void initialGrantSkippedWhenUserAlreadyHasLiability() {
        when(repository.hasLiabilityEntries(0L, 9L)).thenReturn(true);

        assertThat(service.postInitialGrant(0L, 9L, new BigDecimal("5.00"), "g1")).isFalse();
        verify(repository, never()).insertTransaction(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void initialGrantPostsPromoAndLiability() {
        when(repository.hasLiabilityEntries(0L, 9L)).thenReturn(false);
        when(repository.transactionExists(0L, "grant:g1")).thenReturn(false);

        assertThat(service.postInitialGrant(0L, 9L, new BigDecimal("5.00"), "g1")).isTrue();

        verify(repository, org.mockito.Mockito.times(2)).insertEntry(eq(100L), anyLong(),
                org.mockito.ArgumentMatchers.anyString(), any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void summaryComputesDiffsAgainstMeteringAndPaidOrders() {
        when(repository.balanceByCode(0L, "CREDIT_LIABILITY")).thenReturn(new BigDecimal("120.00"));
        when(repository.meteringBalance(0L)).thenReturn(new BigDecimal("118.00"));
        when(repository.balanceByCode(0L, "PSP_CLEARING")).thenReturn(new BigDecimal("90.00"));
        when(repository.paidOrders(0L)).thenReturn(new BigDecimal("89.00"));
        when(repository.unbalancedTransactions()).thenReturn(0);
        when(repository.recentTransactions(0L, 20)).thenReturn(List.of());

        FundLedgerSummary summary = service.summary(0L);

        assertThat(summary.liabilityDiff()).isEqualByComparingTo("2.00");
        assertThat(summary.cashDiff()).isEqualByComparingTo("1.00");
        assertThat(summary.unbalanced()).isZero();
    }

    @Test
    void unbalancedPostingsAreRejected() {
        // 到账额度为 0 的充值：没有负债分录，借贷不平必须在写库前拒绝
        assertThatThrownBy(() -> service.postTopUp(0L, 9L, 1000L, 0L, "M4"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
