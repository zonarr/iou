package io.github.zonarr.iou.audit;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the audit log. Always called inside the transaction that makes the change, so a
 * change and its history line are saved together or not at all.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final JsonMapper json;

    public AuditService(AuditLogRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * @param involved everybody the entry is about (plus the actor), so that entries outside
     *                 groups can be shown to exactly those people
     * @param before   how the entry looked before the change; null when it is being created
     * @param after    how it looks now; null when it is being deleted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntity entity, long entityId, AuditAction action, Long groupId, long actorId,
            Collection<Long> involved, Map<String, Object> before, Map<String, Object> after) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(involved);
        ids.add(actorId);
        repository.save(new AuditLog(entity, entityId, action, groupId, actorId, ids.toArray(Long[]::new),
                before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after)));
    }
}
