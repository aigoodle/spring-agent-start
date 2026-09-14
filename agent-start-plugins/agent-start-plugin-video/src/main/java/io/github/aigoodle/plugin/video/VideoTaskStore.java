package io.github.aigoodle.plugin.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.plugin.video.entity.VideoTaskEntity;
import io.github.aigoodle.plugin.video.mapper.VideoTaskMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import java.util.*;

/** Durable submission reservation plus leased polling/cancellation queue. */
public final class VideoTaskStore {
    public record Task(String id, String tenant, String owner, String requestHash, String endpointCipher,
                       String vendorId, String status, String result, String error, boolean cancel,
                       int attempts, Instant deadline) {
        @Override public String toString() { return "VideoTask[id=" + id + ", status=" + status + "]"; }
    }
    private final VideoTaskMapper mapper;
    private final TransactionTemplate tx;
    public VideoTaskStore(VideoTaskMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        tx = new TransactionTemplate(transactionManager);
        // Commit before vendor HTTP, and isolate a duplicate-key failure from the caller transaction.
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public boolean reserve(String id, String invocationKey, String tenant, String owner, String hash, String cipher) {
        var entity = new VideoTaskEntity();
        entity.setId(id); entity.setInvocationKey(invocationKey); entity.setTenantId(tenant);
        entity.setOwnerId(owner); entity.setRequestHash(hash); entity.setEndpointCipher(cipher);
        entity.setStatus("SUBMITTING"); entity.setCancelRequested(0); entity.setAttempts(0);
        entity.setCreatedAt(java.time.LocalDateTime.now()); entity.setUpdatedAt(entity.getCreatedAt());
        entity.setNextPollAt(Instant.now().plusSeconds(60)); entity.setDeadlineAt(Instant.now().plusSeconds(3600));
        try { tx.executeWithoutResult(status -> mapper.insert(entity)); return true; }
        catch (DuplicateKeyException duplicate) { return false; }
    }
    public Task get(String tenant, String id) {
        return tx.execute(status -> task(mapper.selectOne(new LambdaQueryWrapper<VideoTaskEntity>()
                .eq(VideoTaskEntity::getTenantId, tenant).eq(VideoTaskEntity::getId, id))));
    }
    public Task byInvocation(String tenant, String key) {
        return tx.execute(status -> task(mapper.selectOne(new LambdaQueryWrapper<VideoTaskEntity>()
                .eq(VideoTaskEntity::getTenantId, tenant).eq(VideoTaskEntity::getInvocationKey, key))));
    }
    public void accepted(String tenant, String id, String vendorId) {
        tx.executeWithoutResult(status -> {
            if (mapper.accept(tenant, id, vendorId, Instant.now()) != 1) throw new IllegalStateException("Submission reservation changed");
        });
    }
    public void unknown(String tenant, String id) { tx.executeWithoutResult(status -> mapper.markUnknown(tenant, id)); }
    public void requestCancel(String tenant, String id) { tx.executeWithoutResult(status -> mapper.requestCancel(tenant, id, Instant.now())); }
    public List<Task> due(int limit) {
        return tx.execute(status -> {
            Instant now = Instant.now();
            mapper.expireSubmissions(now);
            return mapper.selectDue(now, Math.max(1, Math.min(limit, 100))).stream().map(VideoTaskStore::task).toList();
        });
    }
    public String claim(Task task) {
        String token = UUID.randomUUID().toString(); Instant now = Instant.now();
        return tx.execute(status -> mapper.claim(task.tenant(), task.id(), token, now, now.plusSeconds(150)) == 1 ? token : null);
    }
    public void finish(Task task, String token, String status, String result, String error, int delaySeconds) {
        tx.executeWithoutResult(transaction -> mapper.finish(task.tenant(), task.id(), token, status, result, error, Instant.now().plusSeconds(delaySeconds)));
    }
    private static Task task(VideoTaskEntity row) {
        return row == null ? null : new Task(row.getId(), row.getTenantId(), row.getOwnerId(), row.getRequestHash(), row.getEndpointCipher(),
                row.getVendorTaskId(), row.getStatus(), row.getResultJson(), row.getLastError(), Integer.valueOf(1).equals(row.getCancelRequested()),
                row.getAttempts() == null ? 0 : row.getAttempts(), row.getDeadlineAt());
    }
}
