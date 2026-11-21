package com.wherelee.base.modules.identity.invitation;

import com.wherelee.base.modules.identity.invitation.dto.UserInvitationDtos;
import com.wherelee.base.modules.identity.security.ClientIpResolver;
import com.wherelee.base.modules.identity.security.PublicRequestThrottleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/invitations")
public class UserInvitationPublicController {

    private final UserInvitationService service;
    private final ClientIpResolver clientIpResolver;
    private final PublicRequestThrottleService throttleService;
    private final InvitationProperties properties;

    public UserInvitationPublicController(UserInvitationService service,
                                             ClientIpResolver clientIpResolver,
                                             PublicRequestThrottleService throttleService,
                                             InvitationProperties properties) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
        this.throttleService = throttleService;
        this.properties = properties;
    }

    @PostMapping("/preview")
    public UserInvitationDtos.PreviewResponse preview(
            @Valid @RequestBody UserInvitationDtos.PreviewRequest request,
            HttpServletRequest httpRequest) {
        throttle("invite-preview", httpRequest);
        return service.preview(request.token());
    }

    @PostMapping("/accept")
    public Long accept(@Valid @RequestBody UserInvitationDtos.AcceptRequest request,
                       HttpServletRequest httpRequest) {
        throttle("invite-accept", httpRequest);
        return service.accept(request);
    }

    private void throttle(String scope, HttpServletRequest request) {
        throttleService.check(scope, clientIpResolver.resolve(request),
                properties.safeMaxAttempts(), properties.safeWindowMinutes());
    }
}
