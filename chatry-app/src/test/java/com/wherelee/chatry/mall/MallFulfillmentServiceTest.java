package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.chatry.ledger.FundLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MallFulfillmentServiceTest {

    @Mock
    private MallOrderRepository orders;
    @Mock
    private MeteringAdminOperations meteringAdmin;
    @Mock
    private FundLedgerService fundLedger;

    private MallFulfillmentService service;

    @BeforeEach
    void setUp() {
        service = new MallFulfillmentService(orders, meteringAdmin, fundLedger);
    }

    @Test
    void rechargesCreditsWithIdempotentBizNoAndMarksPaid() {
        MallOrder order = new MallOrder(1, 0, "M261121000001ABC", 9, 4, "秒杀", 190, 3000,
                MallOrder.STATUS_PAYING, "P-1", true, Instant.now().plusSeconds(600), null, Instant.now());
        when(orders.findByNo(0, "M261121000001ABC")).thenReturn(Optional.of(order));
        when(orders.markPaid(eq(0L), eq("M261121000001ABC"), any(Instant.class))).thenReturn(1);

        service.fulfil(0, "M261121000001ABC");

        ArgumentCaptor<RechargeCommand> captor = ArgumentCaptor.forClass(RechargeCommand.class);
        verify(meteringAdmin).recharge(captor.capture());
        RechargeCommand command = captor.getValue();
        assertThat(command.amount()).isEqualByComparingTo("30.00");
        assertThat(command.currency()).isEqualTo("CNY");
        assertThat(command.bizNo()).isEqualTo("mall:M261121000001ABC");
        assertThat(command.subject().userId()).isEqualTo(9);
        verify(orders).markPaid(eq(0L), eq("M261121000001ABC"), any(Instant.class));
        verify(fundLedger).postTopUp(0L, 9L, 190L, 3000L, "M261121000001ABC");
    }


    @Test
    void closedOrderIsMarkedPaidAfterCloseWithoutCrediting() {
        MallOrder order = new MallOrder(1, 0, "M-CLOSED", 9, 4, "额度包", 999, 1200,
                MallOrder.STATUS_CLOSED, "P-1", false, Instant.now().minusSeconds(1), null, Instant.now());
        when(orders.findByNo(0, "M-CLOSED")).thenReturn(Optional.of(order));
        when(orders.markPaidAfterClose(eq(0L), eq("M-CLOSED"), any(Instant.class))).thenReturn(1);

        service.fulfil(0, "M-CLOSED");

        verify(meteringAdmin, never()).recharge(any());
        verify(orders).markPaidAfterClose(eq(0L), eq("M-CLOSED"), any(Instant.class));
        verify(orders, never()).markPaid(anyLong(), any(), any(Instant.class));
    }

    @Test
    void concurrentCloseWinsBeforeCasMarksAfterClose() {
        MallOrder paying = new MallOrder(1, 0, "M-RACE", 9, 4, "额度包", 999, 1200,
                MallOrder.STATUS_PAYING, "P-1", false, Instant.now().plusSeconds(600), null, Instant.now());
        MallOrder closed = new MallOrder(1, 0, "M-RACE", 9, 4, "额度包", 999, 1200,
                MallOrder.STATUS_CLOSED, "P-1", false, Instant.now().minusSeconds(1), null, Instant.now());
        when(orders.findByNo(0, "M-RACE")).thenReturn(Optional.of(paying), Optional.of(closed));
        when(orders.markPaid(eq(0L), eq("M-RACE"), any(Instant.class))).thenReturn(0);
        when(orders.markPaidAfterClose(eq(0L), eq("M-RACE"), any(Instant.class))).thenReturn(1);

        service.fulfil(0, "M-RACE");

        verify(meteringAdmin, never()).recharge(any());
        verify(orders).markPaidAfterClose(eq(0L), eq("M-RACE"), any(Instant.class));
    }

    @Test
    void missingOrderFailsSoOutboxCanRetry() {
        when(orders.findByNo(0, "M404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fulfil(0, "M404"))
                .isInstanceOf(IllegalStateException.class);
        verify(meteringAdmin, never()).recharge(any());
        verify(orders, never()).markPaid(anyLong(), any(), any(Instant.class));
    }
}
