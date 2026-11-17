package io.github.zonarr.iou.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.auth.AuthDtos.ChangePasswordRequest;
import io.github.zonarr.iou.auth.AuthDtos.Profile;
import io.github.zonarr.iou.auth.AuthDtos.UpdateProfileRequest;
import io.github.zonarr.iou.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Me", description = "The logged-in user")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AuthService auth;

    public MeController(AuthService auth) {
        this.auth = auth;
    }

    @Operation(summary = "My profile")
    @GetMapping
    public Profile me(Actor actor) {
        return auth.profile(actor.id());
    }

    @Operation(summary = "Change my display name")
    @PatchMapping
    public Profile rename(Actor actor, @Valid @RequestBody UpdateProfileRequest request) {
        return auth.rename(actor.id(), request.displayName());
    }

    @Operation(summary = "Change my password")
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Actor actor, @Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(actor.id(), request.currentPassword(), request.newPassword());
    }
}
