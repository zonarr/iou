package io.github.zonarr.iou.user;

import io.github.zonarr.iou.common.ApiException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Looks people up for display and for validating who a debt or expense refers to. */
@Component
public class UserDirectory {

    private final UserRepository users;

    public UserDirectory(UserRepository users) {
        this.users = users;
    }

    public Map<Long, UserSummary> summaries(Collection<Long> ids) {
        Map<Long, UserSummary> result = new HashMap<>();
        users.findAllById(new HashSet<>(ids)).forEach(u -> result.put(u.getId(), UserSummary.of(u)));
        return result;
    }

    public User require(long id) {
        return users.findById(id).filter(u -> u.getMergedInto() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "Person not found"));
    }

    /** A registered person (not a placeholder): the only kind allowed outside groups. */
    public User requireAccount(long id) {
        User user = require(id);
        if (user.isPlaceholder()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "Person not found");
        }
        return user;
    }
}
