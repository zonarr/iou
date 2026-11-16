package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.auth.AuthService;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.GroupService;
import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.user.UserRepository;
import io.github.zonarr.iou.user.UserSummary;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Small lookups the forms need: currencies, remembered rates, finding a person by email. */
@RestController
@RequestMapping("/api")
public class ReferenceController {

    /** Shown first in the app: home currency plus the trip's currencies. Any ISO code is accepted. */
    private static final List<String> FEATURED = List.of("THB", "EUR", "CHF");

    private final RateService rates;
    private final GroupService groups;
    private final UserRepository users;

    public ReferenceController(RateService rates, GroupService groups, UserRepository users) {
        this.rates = rates;
        this.groups = groups;
        this.users = users;
    }

    public record CurrencyInfo(String code, String name) {
    }

    public record Currencies(String base, List<String> featured, List<CurrencyInfo> all) {
    }

    @GetMapping("/currencies")
    public Currencies currencies() {
        List<CurrencyInfo> all = Currency.getAvailableCurrencies().stream()
                .filter(c -> c.getNumericCode() > 0 && !c.getCurrencyCode().startsWith("X"))
                .map(c -> new CurrencyInfo(c.getCurrencyCode(), c.getDisplayName(java.util.Locale.ENGLISH)))
                .sorted(Comparator.comparing(CurrencyInfo::code))
                .toList();
        return new Currencies(Money.BASE_CURRENCY, FEATURED, all);
    }

    /** The last exchange rate used in the group for each currency, to pre-fill the form. */
    @GetMapping("/groups/{groupId}/rates")
    public Map<String, BigDecimal> groupRates(Actor actor, @PathVariable long groupId) {
        groups.requireMember(groupId, actor.id());
        return rates.lastUsedInGroup(groupId);
    }

    /** The last exchange rate I entered for each currency (for entries outside groups). */
    @GetMapping("/me/rates")
    public Map<String, BigDecimal> myRates(Actor actor) {
        return rates.lastUsedBy(actor.id());
    }

    /** Finds a registered person by their exact email, to record a one-on-one debt with them. */
    @GetMapping("/users/lookup")
    public UserSummary lookup(@RequestParam String email) {
        return users.findByEmail(AuthService.normalizeEmail(email))
                .map(UserSummary::of)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found",
                        "Nobody has registered with that email yet"));
    }
}
