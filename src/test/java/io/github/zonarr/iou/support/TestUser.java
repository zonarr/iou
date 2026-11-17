package io.github.zonarr.iou.support;

/** A freshly registered account plus an {@link Api} that calls the server as that account. */
public record TestUser(long id, String email, String password, String name, String token, Api api) {
}
