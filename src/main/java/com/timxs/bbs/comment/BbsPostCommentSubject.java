package com.timxs.bbs.comment;

import com.timxs.bbs.extension.BbsPost;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;
import run.halo.app.content.comment.CommentSubject;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

/**
 * 把 BBS 帖子注册为 Halo 评论体系的评论主体：
 * 帖子详情页的评论由 Halo 核心 + 评论组件插件渲染与管理，
 * 是否需要登录 / 是否审核由站点「系统设置 → 评论」统一控制。
 *
 * @author Tim0x0
 */
@Component
public class BbsPostCommentSubject implements CommentSubject<BbsPost> {

    private static final GroupVersionKind GVK = GroupVersionKind.fromExtension(BbsPost.class);

    private final ReactiveExtensionClient client;

    public BbsPostCommentSubject(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Mono<BbsPost> get(String name) {
        return client.fetch(BbsPost.class, name);
    }

    @Override
    public Mono<SubjectDisplay> getSubjectDisplay(String name) {
        return get(name).map(post -> new SubjectDisplay(
                post.getSpec().getTitle(),
                "/bbs/post/" + post.getSpec().getSlug(),
                "BBS 帖子"));
    }

    @Override
    public boolean supports(Ref ref) {
        Assert.notNull(ref, "Subject ref must not be null.");
        return Objects.equals(GVK.group(), ref.getGroup())
                && Objects.equals(GVK.kind(), ref.getKind());
    }
}
