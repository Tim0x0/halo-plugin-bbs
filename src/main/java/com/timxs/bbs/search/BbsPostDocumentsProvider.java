package com.timxs.bbs.search;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.service.HtmlSanitizer;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.HaloDocumentsProvider;

/**
 * 把帖子接入 Halo 全文搜索：站点全局搜索（主题搜索框 / 搜索页）可搜到
 * 已发布帖子的标题与正文，结果直达 {@code /bbs/post/{slug}}。
 *
 * <p>全量索引重建走本 Provider；增量更新 / 删除由 {@code BbsPostReconciler}
 * 发布搜索事件驱动。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsPostDocumentsProvider implements HaloDocumentsProvider {

    /** 搜索文档类型标识（同时作为文档 ID 前缀）。 */
    public static final String TYPE = "bbspost.bbs.timxs.com";

    private final ReactiveExtensionClient client;

    public BbsPostDocumentsProvider(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Flux<HaloDocument> fetchAll() {
        return client.listAll(BbsPost.class, new ListOptions(), Sort.unsorted())
                .filter(post -> post.getMetadata().getDeletionTimestamp() == null)
                .map(BbsPostDocumentsProvider::convert);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /** 文档全局唯一 ID（Provider 与 Reconciler 事件共用同一口径）。 */
    public static String docId(String metadataName) {
        return TYPE + "-" + metadataName;
    }

    /** 帖子 → 搜索文档：正文转纯文本；仅已发布帖子对外可见。 */
    public static HaloDocument convert(BbsPost post) {
        var spec = post.getSpec();
        boolean published = spec.getPhase() == BbsPost.Phase.PUBLISHED;
        var doc = new HaloDocument();
        doc.setId(docId(post.getMetadata().getName()));
        doc.setMetadataName(post.getMetadata().getName());
        doc.setTitle(spec.getTitle());
        doc.setDescription(spec.getExcerpt());
        doc.setContent(HtmlSanitizer.plainText(spec.getContent()));
        doc.setOwnerName(spec.getOwner());
        doc.setCategories(spec.getCategoryName() == null
                ? null : List.of(spec.getCategoryName()));
        doc.setCreationTimestamp(post.getMetadata().getCreationTimestamp());
        doc.setUpdateTimestamp(spec.getLastEditTime() != null
                ? spec.getLastEditTime()
                : spec.getPublishTime() != null
                        ? spec.getPublishTime()
                        : post.getMetadata().getCreationTimestamp());
        doc.setPermalink("/bbs/post/" + spec.getSlug());
        doc.setType(TYPE);
        doc.setPublished(published);
        doc.setRecycled(false);
        doc.setExposed(published);
        return doc;
    }
}
