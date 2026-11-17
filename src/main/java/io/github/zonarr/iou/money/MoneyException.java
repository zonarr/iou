package io.github.zonarr.iou.money;

/** A money rule was broken by the input. Carries a stable code the API passes to the client. */
public class MoneyException extends RuntimeException {

    private final String code;

    public MoneyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
