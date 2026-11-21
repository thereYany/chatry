package com.wherelee.chatry.chat.web;

import com.wherelee.chatry.credits.CreditService;
import com.wherelee.chatry.credits.CreditsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 额度接口：余额、冻结、新手赠送与按单价估算的可对话次数。 */
@RestController
@RequestMapping("/api/chat")
public class CreditsController {

    private final CreditService credits;

    public CreditsController(CreditService credits) {
        this.credits = credits;
    }

    @GetMapping("/credits")
    public CreditsView credits(@RequestAttribute("tenantId") long tenantId,
                               @RequestAttribute("userId") long userId) {
        return credits.credits(tenantId, userId);
    }
}
