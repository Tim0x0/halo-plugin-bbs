package com.timxs.bbs.reconciler;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.springframework.dao.OptimisticLockingFailureException;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;

/**
 * Extension 乐观锁更新：重新 fetch → 改 → update，指数退避重试。
 *
 * <p>评论调和与帖子调和会同时写同一条 {@code BbsPost}，冲突是常态。
 * 必须从重新读取开始拿新 version，直接重放旧对象永远失败。</p>
 *
 * @author Tim0x0
 */
public final class OptimisticUpdates {

    private static final int RETRIES = 5;

    private OptimisticUpdates() {
    }

    /**
     * @return true 已写入或无需写入；对象不存在返回 false
     */
    public static <T extends Extension> boolean update(ExtensionClient client, Class<T> type,
            String name, Consumer<T> mutation) {
        return update(client, type, name, mutation, OptimisticUpdates::sleep);
    }

    static <T extends Extension> boolean update(ExtensionClient client, Class<T> type,
            String name, Consumer<T> mutation, IntConsumer backoff) {
        OptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            var found = client.fetch(type, name);
            if (found.isEmpty()) {
                return false;
            }
            try {
                var ext = found.get();
                mutation.accept(ext);
                client.update(ext);
                return true;
            } catch (OptimisticLockingFailureException e) {
                last = e;
                // 最后一次已没有下一轮，不能再白等 800ms。
                if (attempt + 1 < RETRIES) {
                    backoff.accept(attempt);
                }
            }
        }
        if (last != null) {
            throw last;
        }
        return false;
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(50L * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("乐观锁重试被中断", e);
        }
    }
}
