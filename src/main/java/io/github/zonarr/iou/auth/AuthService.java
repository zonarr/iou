package io.github.zonarr.iou.auth;

import io.github.zonarr.iou.auth.AuthDtos.AuthResponse;
import io.github.zonarr.iou.auth.AuthDtos.Profile;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.user.User;
import io.github.zonarr.iou.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    /** Compared against when the email is unknown, so both cases take the same time. */
    private final String dummyHash;
    private final boolean registrationOpen;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwt,
            @org.springframework.beans.factory.annotation.Value("${iou.registration-open:true}") boolean registrationOpen) {
        this.registrationOpen = registrationOpen;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public AuthResponse register(String email, String password, String displayName) {
        if (!registrationOpen) {
            throw ApiException.forbidden("registration_closed", "New accounts are not being accepted");
        }
        requireUsablePassword(password);
        String normalized = normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
        User user = users.save(User.account(normalized, passwordEncoder.encode(password), displayName.trim()));
        return respond(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String email, String password) {
        User user = users.findByEmail(normalizeEmail(email)).orElse(null);
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = fitsBcrypt(password) && passwordEncoder.matches(password, hash);
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Wrong email or password");
        }
        return respond(user);
    }

    @Transactional(readOnly = true)
    public Profile profile(long userId) {
        return toProfile(load(userId));
    }

    @Transactional
    public Profile rename(long userId, String displayName) {
        User user = load(userId);
        user.setDisplayName(displayName.trim());
        return toProfile(user);
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        requireUsablePassword(newPassword);
        User user = load(userId);
        if (!fitsBcrypt(currentPassword) || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("wrong_password", "The current password is wrong");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    private User load(long userId) {
        // A token can outlive its account only if the row was removed by hand; treat as logged out.
        return users.findById(userId).filter(u -> !u.isPlaceholder())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Please log in again"));
    }

    private AuthResponse respond(User user) {
        JwtService.IssuedToken token = jwt.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), toProfile(user));
    }

    private static Profile toProfile(User user) {
        return new Profile(user.getId(), user.getEmail(), user.getDisplayName());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private static void requireUsablePassword(String password) {
        if (!fitsBcrypt(password)) {
            throw ApiException.badRequest("password_too_long", "The password is too long");
        }
    }
}
