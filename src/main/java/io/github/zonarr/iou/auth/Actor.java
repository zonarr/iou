package io.github.zonarr.iou.auth;

/**
 * The logged-in user making the current request. Declare it as a controller method
 * parameter; {@link ActorArgumentResolver} fills it in from the JWT.
 */
public record Actor(long id) {
}
