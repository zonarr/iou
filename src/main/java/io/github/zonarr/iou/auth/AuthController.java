package io.github.zonarr.iou.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.AuthDtos.AuthResponse;
import io.github.zonarr.iou.auth.AuthDtos.LoginRequest;
import io.github.zonarr.iou.auth.AuthDtos.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Create an account and log in")
@SecurityRequirements // public: no token needed
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Creates an account and logs it in (the response carries a token, like login). */
    @Operation(summary = "Create an account (returns a token, so the new user is logged in)")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request.email(), request.password(), request.displayName());
    }

    @Operation(summary = "Log in and get a JWT")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }
}
