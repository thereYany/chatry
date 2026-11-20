package com.wherelee.chatry.ledger;

import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端：资金账本汇总与对账差异。 */
@RestController
@RequestMapping("/api/admin")
public class AdminFundLedgerController {

    private final FundLedgerService service;

    public AdminFundLedgerController(FundLedgerService service) {
        this.service = service;
    }

    @GetMapping("/fund-ledger/summary")
    @RequirePermission(PermissionCodes.ADMIN_FINANCE_VIEW)
    public FundLedgerSummary summary(@RequestAttribute("tenantId") long tenantId) {
        return service.summary(tenantId);
    }
}
