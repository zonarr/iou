package io.github.zonarr.iou.audit;

import io.github.zonarr.iou.audit.ActivityDtos.ActivityPage;
import io.github.zonarr.iou.auth.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Activity", description = "The history timeline, built on the audit log")
@RestController
@RequestMapping("/api")
public class ActivityController {

    private final ActivityService activity;

    public ActivityController(ActivityService activity) {
        this.activity = activity;
    }

    @Operation(summary = "History of a group, newest first (pass nextBefore as before for older entries)")
    @GetMapping("/groups/{id}/activity")
    public ActivityPage group(Actor actor, @PathVariable long id,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Integer limit) {
        return activity.forGroup(actor.id(), id, before, limit);
    }

    @Operation(summary = "My history: all my groups plus one-on-one entries that involve me")
    @GetMapping("/me/activity")
    public ActivityPage mine(Actor actor,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Integer limit) {
        return activity.forUser(actor.id(), before, limit);
    }
}
