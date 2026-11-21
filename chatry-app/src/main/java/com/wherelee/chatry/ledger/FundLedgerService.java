package com.wherelee.chatry.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 资金侧双分录账本。
 *
 * <p>账户：PSP_CLEARING（资产/借）、CREDIT_LIABILITY（负债/贷，按用户）、REVENUE（收入/贷）、
 * PROMO_EXPENSE（营销费用/借）、OPENING_EQUITY（期初权益/贷）。每笔交易借贷必须相等；
 * 所有写入以 eventKey 幂等；余额由分录派生，不直接改账户余额。</p>
 */
@Service
public class FundLedgerService {

    private static final Logger log = LoggerFactory.getLogger(FundLedgerService.class);

    private static final String CLEARING = "PSP_CLEARING";
    private static final String LIABILITY = "CREDIT_LIABILITY";
    private static final String REVENUE = "REVENUE";
    private static final String PROMO = "PROMO_EXPENSE";
    private static final String OPENING = "OPENING_EQUITY";
    private static final String REFUND_PAYABLE = "REFUND_PAYABLE";
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";
    private static final String CNY = "CNY";

    private final FundLedgerRepository repository;

    public FundLedgerService(FundLedgerRepository repository) {
        this.repository = repository;
    }

    /** 支付成功：借清算（实付）+ 借营销费用（赠送），贷额度负债（到账额度）。 */
    @Transactional
    public boolean postTopUp(long tenantId, long userId, long amountCents, long creditCents, String orderNo) {
        BigDecimal paid = BigDecimal.valueOf(amountCents, 2);
        BigDecimal credit = BigDecimal.valueOf(creditCents, 2);
        if (paid.signum() < 0 || credit.signum() <= 0) {
            throw new IllegalArgumentException("充值金额非法");
        }
        BigDecimal bonus = credit.subtract(paid);
        List<Posting> postings = new ArrayList<>();
        if (paid.signum() > 0) {
            postings.add(new Posting(CLEARING, 0L, "ASSET", DEBIT, DEBIT, paid));
        }
        if (bonus.signum() > 0) {
            postings.add(new Posting(PROMO, 0L, "EXPENSE", DEBIT, DEBIT, bonus));
        } else if (bonus.signum() < 0) {
            // 到账额度低于实付：差额即时确认收入，避免账不平
            postings.add(new Posting(REVENUE, 0L, "REVENUE", CREDIT, CREDIT, bonus.negate()));
        }
        postings.add(new Posting(LIABILITY, userId, "LIABILITY", CREDIT, CREDIT, credit));
        return post(tenantId, "topup:" + orderNo, "CREDIT_TOPUP", orderNo,
                "额度充值 " + orderNo, postings);
    }

    /** 新手赠送：借营销费用，贷额度负债。已有负债分录（含期初回填）时跳过。 */
    @Transactional
    public boolean postInitialGrant(long tenantId, long userId, BigDecimal amount, String grantBizNo) {
        if (amount == null || amount.signum() <= 0 || repository.hasLiabilityEntries(tenantId, userId)) {
            return false;
        }
        return post(tenantId, "grant:" + grantBizNo, "INITIAL_GRANT", grantBizNo,
                "新手额度赠送", List.of(
                        new Posting(PROMO, 0L, "EXPENSE", DEBIT, DEBIT, amount),
                        new Posting(LIABILITY, userId, "LIABILITY", CREDIT, CREDIT, amount)));
    }

    /** 对话结算：借额度负债，贷收入。 */
    @Transactional
    public boolean postUsage(long tenantId, long userId, BigDecimal amount, String settleBizNo,
                             String currency) {
        if (amount == null || amount.signum() <= 0) {
            return false;
        }
        return post(tenantId, "usage:" + settleBizNo, "CREDIT_USAGE", settleBizNo,
                "对话额度消耗", List.of(
                        new Posting(LIABILITY, userId, "LIABILITY", CREDIT, DEBIT, amount),
                        new Posting(REVENUE, 0L, "REVENUE", CREDIT, CREDIT, amount)));
    }

    /**
     * 退款冲销：退回实付、扣回额度、冲回赠送成本。
     *
     * <p>借 额度负债（扣回额度）＝ 贷 清算（退回实付）+ 贷 营销费用（冲回赠送）。
     * 扣回额度 ≥ 实付时成立（充值额度不低于实付金额是当前商品规则）。</p>
     */
    @Transactional
    public boolean postRefund(long tenantId, long userId, long refundAmountCents,
                              long orderCreditCents, long deductCreditCents, String refundNo) {
        BigDecimal cash = BigDecimal.valueOf(refundAmountCents, 2);
        BigDecimal deduct = BigDecimal.valueOf(deductCreditCents, 2);
        if (cash.signum() <= 0) {
            return false;
        }
        if (deduct.compareTo(cash) < 0) {
            // 扣回额度小于实付：差额冲减收入（部分退款且额度已被消费到低于实付）
            BigDecimal revenue = cash.subtract(deduct);
            return post(tenantId, "refund:" + refundNo, "REFUND", refundNo,
                    "退款 " + refundNo, List.of(
                            new Posting(LIABILITY, userId, "LIABILITY", CREDIT, DEBIT, deduct),
                            new Posting(REVENUE, 0L, "REVENUE", CREDIT, DEBIT, revenue),
                            new Posting(CLEARING, 0L, "ASSET", DEBIT, CREDIT, cash)));
        }
        BigDecimal promoReversal = deduct.subtract(cash);
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(LIABILITY, userId, "LIABILITY", CREDIT, DEBIT, deduct));
        postings.add(new Posting(CLEARING, 0L, "ASSET", DEBIT, CREDIT, cash));
        if (promoReversal.signum() > 0) {
            postings.add(new Posting(PROMO, 0L, "EXPENSE", DEBIT, CREDIT, promoReversal));
        }
        return post(tenantId, "refund:" + refundNo, "REFUND", refundNo, "退款 " + refundNo, postings);
    }

    /** 关单后收到支付：钱已收到但额度不发，记「待退款负债」，等退款时冲掉。 */
    @Transactional
    public boolean postPaidAfterClose(long tenantId, long amountCents, String orderNo) {
        BigDecimal cash = BigDecimal.valueOf(amountCents, 2);
        if (cash.signum() <= 0) {
            return false;
        }
        return post(tenantId, "paid-after-close:" + orderNo, "PAID_AFTER_CLOSE", orderNo,
                "关单后收到支付 " + orderNo, List.of(
                        new Posting(CLEARING, 0L, "ASSET", DEBIT, DEBIT, cash),
                        new Posting(REFUND_PAYABLE, 0L, "LIABILITY", CREDIT, CREDIT, cash)));
    }

    /** 关单后退款：冲掉待退款负债、退还实付。 */
    @Transactional
    public boolean postRefundNoCredit(long tenantId, long refundAmountCents, String refundNo) {
        BigDecimal cash = BigDecimal.valueOf(refundAmountCents, 2);
        if (cash.signum() <= 0) {
            return false;
        }
        return post(tenantId, "refund:" + refundNo, "REFUND_NO_CREDIT", refundNo,
                "关单后退款 " + refundNo, List.of(
                        new Posting(REFUND_PAYABLE, 0L, "LIABILITY", CREDIT, DEBIT, cash),
                        new Posting(CLEARING, 0L, "ASSET", DEBIT, CREDIT, cash)));
    }

    /** 按 metering 的结算流水即时补记；没有结算流水说明本次未落账，交给 sweep 任务。 */
    @Transactional
    public boolean syncUsage(String settleBizNo) {
        FundLedgerRepository.UsageRow usage = repository.findUsage(settleBizNo);
        if (usage == null) {
            return false;
        }
        return postUsage(usage.tenantId(), usage.userId(), usage.amount(), usage.bizNo(), usage.currency());
    }

    /**
     * 期初回填：P7 上线前的历史余额/已支付订单没有资金分录。
     * 用 OPENING_EQUITY 吸收历史差额，之后的新交易正常记账；只执行一次。
     */
    @Transactional
    public void backfillOpening() {
        if ("1".equals(repository.meta("opening_done"))) {
            return;
        }
        int credits = 0;
        for (FundLedgerRepository.OpeningCredit row : repository.openingCredits()) {
            if (post(row.tenantId(), "opening:credit:" + row.accountId(), "OPENING_CREDIT",
                    String.valueOf(row.accountId()), "期初额度余额", List.of(
                            new Posting(OPENING, 0L, "EQUITY", CREDIT, DEBIT, row.balance()),
                            new Posting(LIABILITY, row.userId(), "LIABILITY", CREDIT, CREDIT, row.balance())))) {
                credits++;
            }
        }
        int cash = 0;
        for (FundLedgerRepository.OpeningCash row : repository.openingCash()) {
            BigDecimal amount = BigDecimal.valueOf(row.amountCents(), 2);
            if (post(row.tenantId(), "opening:cash:" + row.orderNo(), "OPENING_CASH",
                    row.orderNo(), "期初已支付订单", List.of(
                            new Posting(CLEARING, 0L, "ASSET", DEBIT, DEBIT, amount),
                            new Posting(OPENING, 0L, "EQUITY", CREDIT, CREDIT, amount)))) {
                cash++;
            }
        }
        repository.setMeta("opening_done", "1");
        repository.setMeta("opening_at", LocalDateTime.now().toString());
        log.info("fund.ledger.opening_backfill credits={} cashOrders={}", credits, cash);
    }

    public LocalDateTime openingAt() {
        String value = repository.meta("opening_at");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<FundLedgerRepository.UsageRow> pendingUsage(LocalDateTime since, int limit) {
        return repository.pendingUsage(since, limit);
    }

    public FundLedgerSummary summary(long tenantId) {
        BigDecimal liability = repository.balanceByCode(tenantId, LIABILITY);
        BigDecimal metering = repository.meteringBalance(tenantId);
        BigDecimal clearing = repository.balanceByCode(tenantId, CLEARING);
        BigDecimal paidOrders = repository.paidOrders(tenantId);
        return new FundLedgerSummary(
                liability, metering, clearing, paidOrders,
                liability.subtract(metering), clearing.subtract(paidOrders),
                repository.unbalancedTransactions(), LocalDate.now(),
                repository.recentTransactions(tenantId, 20));
    }

    public List<Long> tenantIds() {
        return repository.tenantIds();
    }

    public FundLedgerSummary check(long tenantId) {
        FundLedgerSummary summary = summary(tenantId);
        repository.upsertCheck(tenantId, summary.checkDate(), summary);
        return summary;
    }

    private boolean post(long tenantId, String eventKey, String bizType, String bizNo,
                         String description, List<Posting> postings) {
        if (repository.transactionExists(tenantId, eventKey)) {
            return false;
        }
        BigDecimal debit = postings.stream().filter(Posting::isDebit)
                .map(Posting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = postings.stream().filter(p -> !p.isDebit())
                .map(Posting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debit.compareTo(credit) != 0) {
            throw new IllegalStateException("资金账本借贷不平 event=" + eventKey
                    + " debit=" + debit + " credit=" + credit);
        }
        long transactionId;
        try {
            transactionId = repository.insertTransaction(tenantId, eventKey, bizType, bizNo,
                    description, LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            return false;
        }
        for (Posting posting : postings) {
            if (posting.amount().signum() <= 0) {
                continue;
            }
            long accountId = repository.ensureAccount(tenantId, posting.code(), posting.ownerId(),
                    CNY, posting.accountType(), posting.normality());
            repository.insertEntry(transactionId, accountId, posting.direction(),
                    posting.amount().setScale(6, RoundingMode.HALF_UP), CNY);
        }
        return true;
    }

    private record Posting(String code, long ownerId, String accountType, String normality,
                           String direction, BigDecimal amount) {
        boolean isDebit() {
            return DEBIT.equals(direction);
        }
    }
}
