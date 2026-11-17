package io.github.zonarr.iou.support;

public record TestGroup(long id, String inviteCode) {

    public String path() {
        return "/api/groups/" + id;
    }
}
