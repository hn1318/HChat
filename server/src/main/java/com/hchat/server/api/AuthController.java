package com.hchat.server.api;

import com.hchat.server.api.dto.AuthDtos.DevTokenRequest;
import com.hchat.server.api.dto.AuthDtos.TokenRequest;
import com.hchat.server.api.dto.AuthDtos.TokenResponse;
import com.hchat.server.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Token 签发")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "业务方后端用 appId + appSecret 换取短期 Token")
    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        return authService.issueToken(request);
    }

    @Operation(summary = "开发模式免密签发（DEV_MODE=true 时可用）")
    @PostMapping("/dev-token")
    public TokenResponse devToken(@Valid @RequestBody DevTokenRequest request) {
        return authService.issueDevToken(request);
    }
}
