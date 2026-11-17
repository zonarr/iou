package io.github.zonarr.iou.audit;

import io.github.zonarr.iou.user.UserSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class ActivityDtos {

    private ActivityDtos() {
    }

    /**
     * One line of history.
     *
     * @param before how the entry looked before the change (null when it was created)
     * @param after  how it looked afterwards (null when it was deleted)
     */
    public record ActivityEntry(
            Long id,
            Instant at,
            AuditEntity entityType,
            Long entityId,
            AuditAction action,
            Long groupId,
            String groupName,
            UserSummary actor,
            JsonNode before,
            JsonNode after) {
    }

    /**
     * A page of history, newest first.
     *
     * @param people     names for every user id that appears inside {@code before}/{@code after}
     * @param nextBefore pass as {@code before} to get the next (older) page; null on the last page
     */
    public record ActivityPage(List<ActivityEntry> entries, Map<Long, String> people, Long nextBefore) {
    }
}
