package com.timxs.bbs.util;

import java.time.Duration;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.util.retry.Retry;

/**
 * 乐观锁重试规格（反应式）：只重试版本冲突，其余异常直接透出。
 *
 * <p>必须从重新读取开始拿新 version，直接重放旧对象永远失败。阻塞版孪生见
 * {@code reconciler.OptimisticUpdates}；重试次数 / 退避基数由调用方按场景传入，
 * 本类不预设策略。各服务「重新 fetch → 改 → update」的循环形态各异
 * （404 语义、TOCTOU 复查、创建兜底），留在各自服务内实现，只共用本规格。</p>
 *
 * @author Tim0x0
 */
public final class ReactiveOptimisticUpdates {

    private ReactiveOptimisticUpdates() {
    }

    /** 只重试乐观锁冲突的重试规格；其余异常直接透出。 */
    public static Retry conflictRetry(int maxAttempts, Duration minBackoff) {
        return Retry.backoff(maxAttempts, minBackoff)
                .filter(OptimisticLockingFailureException.class::isInstance);
    }
}
