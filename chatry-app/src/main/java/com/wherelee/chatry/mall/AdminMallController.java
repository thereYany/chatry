package com.wherelee.chatry.mall;

import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商城（管理端）：建品、秒杀配置、上下架。 */
@RestController
@RequestMapping("/api/admin/mall")
public class AdminMallController {

    private final MallService mall;

    public AdminMallController(MallService mall) {
        this.mall = mall;
    }

    @GetMapping("/skus")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public List<SkuView> skus(@RequestAttribute("tenantId") long tenantId) {
        return mall.listAll(tenantId);
    }

    @PostMapping("/skus")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public SkuView create(@RequestAttribute("tenantId") long tenantId,
                          @RequestBody CreateSkuRequest request) {
        return mall.createSku(tenantId, request);
    }

    @PutMapping("/skus/{id}/status")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public SkuView updateStatus(@RequestAttribute("tenantId") long tenantId,
                                @PathVariable long id,
                                @RequestBody SkuStatusRequest request) {
        return mall.updateStatus(tenantId, id, request.status());
    }
}
