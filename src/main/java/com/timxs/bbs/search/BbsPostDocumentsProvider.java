package com.timxs.bbs.search;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.service.BbsPostContentService;
import com.timxs.bbs.service.HtmlSanitizer;
import com.timxs.bbs.util.BbsExcerpts;
import com.timxs.bbs.util.BbsUrls;
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
    private final BbsPostContentService contentService;

    public BbsPostDocumentsProvider(ReactiveExtensionClient client,
            BbsPostContentService contentService) {
        this.client = client;
        this.contentService = contentService;
    }

    @Override
    public Flux<HaloDocument> fetchAll() {
        // 回收站里的帖子不进索引。增量路径由 BbsPostReconciler 移出索引，但全量重建
        // （重启 / 手动重建）走这里——漏了过滤会把已删除的帖子重新灌回搜索结果
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.deleted", false),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name())))
                .build();
        return client.listAll(BbsPost.class, options, Sort.unsorted())
                .filter(post -> post.getMetadata().getDeletionTimestamp() == null)
                .flatMapSequential(post -> contentService.getReleaseContent(post)
                        .map(content -> convert(post, content.getContent())));
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /** 文档全局唯一 ID（Provider 与 Reconciler 事件共用同一口径）。 */
    public static String docId(String metadataName) {
        return TYPE + "-" + metadataName;
    }

    /** 帖子 → 搜索文档：正文转纯文本；仅已发布且未删除的帖子对外可见。 */
    public static HaloDocument convert(BbsPost post, String content) {
        var spec = post.getSpec();
        boolean recycled = Boolean.TRUE.equals(spec.getDeleted());
        boolean published = spec.getPhase() == BbsPost.Phase.PUBLISHED && !recycled;
        var doc = new HaloDocument();
        doc.setId(docId(post.getMetadata().getName()));
        doc.setMetadataName(post.getMetadata().getName());
        doc.setTitle(spec.getTitle());
        doc.setDescription(BbsExcerpts.resolve(spec, content));
        doc.setContent(HtmlSanitizer.plainText(content));
        doc.setOwnerName(spec.getOwner());
        doc.setCategories(spec.getCategoryName() == null
                ? null : List.of(spec.getCategoryName()));
        doc.setCreationTimestamp(post.getMetadata().getCreationTimestamp());
        doc.setUpdateTimestamp(spec.getLastEditTime() != null
                ? spec.getLastEditTime()
                : spec.getPublishTime() != null
                        ? spec.getPublishTime()
                        : post.getMetadata().getCreationTimestamp());
        doc.setPermalink(BbsUrls.postPermalink(spec.getSlug()));
        doc.setType(TYPE);
        doc.setPublished(published);
        // Halo 的搜索框架原生支持回收站语义：如实上报，万一文档留在索引里也不会被暴露
        doc.setRecycled(recycled);
        doc.setExposed(published);
        return doc;
    }
}
