package com.timxs.bbs.reconciler;

import com.timxs.bbs.event.BbsPostChangedEvent;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.search.BbsPostDocumentsProvider;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.search.event.HaloDocumentAddRequestEvent;
import run.halo.app.search.event.HaloDocumentDeleteRequestEvent;

/**
 * 帖子调和器：把任何路径（服务层 / 直接 CRUD patch / 批量操作）产生的帖子
 * 变更同步进 Halo 搜索索引；删除时先移除索引再释放 finalizer。
 *
 * <p>仅需 {@code @Component}，Halo 会自动注册并随插件启停（勿手动 start/stop）。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsPostReconciler implements Reconciler<Reconciler.Request> {

    private static final String FINALIZER = "bbs.timxs.com/search-index";

    private final ExtensionClient client;
    private final ApplicationEventPublisher eventPublisher;

    public BbsPostReconciler(ExtensionClient client, ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Result reconcile(Request request) {
        client.fetch(BbsPost.class, request.name()).ifPresent(post -> {
            var metadata = post.getMetadata();
            if (ExtensionUtil.isDeleted(post)) {
                // 先移除索引，再释放 finalizer 让删除真正完成
                eventPublisher.publishEvent(new HaloDocumentDeleteRequestEvent(this,
                        List.of(BbsPostDocumentsProvider.docId(metadata.getName()))));
                if (metadata.getFinalizers() != null
                        && metadata.getFinalizers().contains(FINALIZER)) {
                    metadata.getFinalizers().remove(FINALIZER);
                    client.update(post);
                }
                eventPublisher.publishEvent(new BbsPostChangedEvent(this));
                return;
            }
            if (metadata.getFinalizers() == null
                    || !metadata.getFinalizers().contains(FINALIZER)) {
                ExtensionUtil.addFinalizers(metadata, Set.of(FINALIZER));
                client.update(post);
            }
            eventPublisher.publishEvent(new HaloDocumentAddRequestEvent(this,
                    List.of(BbsPostDocumentsProvider.convert(post))));
            eventPublisher.publishEvent(new BbsPostChangedEvent(this));
        });
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new BbsPost())
                .build();
    }
}
