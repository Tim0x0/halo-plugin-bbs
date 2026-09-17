package com.timxs.bbs.service;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.util.BbsUrls;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.ReasonAttributes;
import run.halo.app.notification.UserIdentity;

/**
 * 帖子新评论通知：补齐官方只给核心 Post / SinglePage 发的 {@code new-comment-on-post}。
 *
 * <p>自有 reason type {@code bbs-new-comment-on-post}，属性名与官方完全一致，
 * 文案按帖子口径。创建即发、不看审核状态；评论者是帖子作者本人
 * （含匿名邮箱与作者邮箱相同）不发。</p>
 *
 * <p>楼中楼回复走官方 {@code someone-replied-to-you}，本服务不发。
 * 进邮件的链接一律经 {@link ExternalLinkProcessor} 转绝对地址。</p>
 *
 * @author Tim0x0
 */
@Component
@Slf4j
public class BbsCommentNotificationService {

    public static final String NEW_COMMENT_ON_POST = "bbs-new-comment-on-post";

    private final NotificationCenter notificationCenter;
    private final NotificationReasonEmitter reasonEmitter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ReactiveExtensionClient client;

    public BbsCommentNotificationService(NotificationCenter notificationCenter,
            NotificationReasonEmitter reasonEmitter,
            ExternalLinkProcessor externalLinkProcessor,
            ReactiveExtensionClient client) {
        this.notificationCenter = notificationCenter;
        this.reasonEmitter = reasonEmitter;
        this.externalLinkProcessor = externalLinkProcessor;
        this.client = client;
    }

    /** 为帖子作者订阅新评论通知；无作者则跳过。订阅本身幂等。 */
    public Mono<Void> subscribe(BbsPost post) {
        var owner = post.getSpec() == null ? null : post.getSpec().getOwner();
        if (StringUtils.isBlank(owner)) {
            return Mono.empty();
        }
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(owner);
        var reason = new Subscription.InterestReason();
        reason.setReasonType(NEW_COMMENT_ON_POST);
        reason.setExpression("props.postOwner == '%s'".formatted(owner.replace("'", "\\'")));
        return notificationCenter.subscribe(subscriber, reason).then()
                .onErrorResume(error -> {
                    log.warn("订阅 BBS 新评论通知失败：owner={}", owner, error);
                    return Mono.empty();
                });
    }

    /**
     * 新评论通知帖子作者。对齐官方「创建即发」：不看 approved / hidden；
     * 评论者是作者本人（匿名邮箱与作者邮箱相同也算）不发。
     */
    public Mono<Void> notifyNewComment(BbsPost post, Comment comment) {
        var spec = post.getSpec();
        var commentSpec = comment.getSpec();
        if (spec == null || commentSpec == null || StringUtils.isBlank(spec.getOwner())) {
            return Mono.empty();
        }
        var owner = commentSpec.getOwner();
        return isPostOwner(spec.getOwner(), owner)
                .flatMap(self -> self ? Mono.empty() : emitNewComment(post, comment, owner));
    }

    private Mono<Void> emitNewComment(BbsPost post, Comment comment, Comment.CommentOwner owner) {
        var spec = post.getSpec();
        var title = StringUtils.defaultIfBlank(spec.getTitle(), post.getMetadata().getName());
        var url = postUrl(post);
        var attributes = new ReasonAttributes();
        attributes.put("postName", post.getMetadata().getName());
        attributes.put("postOwner", spec.getOwner());
        attributes.put("postTitle", title);
        attributes.put("postUrl", url);
        attributes.put("commenter", StringUtils.defaultIfBlank(
                owner.getDisplayName(), owner.getName()));
        attributes.put("commentName", comment.getMetadata().getName());
        attributes.put("content", displayContent(comment.getSpec().getContent()));
        var subject = Reason.Subject.builder()
                .apiVersion(post.getApiVersion())
                .kind(post.getKind())
                .name(post.getMetadata().getName())
                .title(title)
                .url(url)
                .build();
        return subscribe(post)
                .then(reasonEmitter.emit(NEW_COMMENT_ON_POST, builder -> builder
                        .attributes(attributes)
                        .author(identityFrom(owner))
                        .subject(subject)));
    }

    /** 对齐官方 isPostOwner：匿名邮箱评论者与作者邮箱相同也视为本人。 */
    private Mono<Boolean> isPostOwner(String postOwner, Comment.CommentOwner commentOwner) {
        var kind = commentOwner.getKind();
        var name = commentOwner.getName();
        if (Comment.CommentOwner.KIND_EMAIL.equals(kind)) {
            return client.fetch(User.class, postOwner)
                    .map(user -> name.equals(user.getSpec().getEmail()))
                    .defaultIfEmpty(false);
        }
        return Mono.just(name.equals(postOwner));
    }

    private static UserIdentity identityFrom(Comment.CommentOwner owner) {
        if (Comment.CommentOwner.KIND_EMAIL.equals(owner.getKind())) {
            return UserIdentity.anonymousWithEmail(owner.getName());
        }
        return UserIdentity.of(owner.getName());
    }

    /** 邮件正文：相对链接转绝对（对齐官方 CommentContentConverter）。 */
    private String displayContent(String content) {
        if (StringUtils.isBlank(content)) {
            return content;
        }
        var parsed = Jsoup.parse(content);
        parsed.select("img").forEach(element ->
                element.attr("src", externalLinkProcessor.processLink(element.attr("src"))));
        return parsed.body().html();
    }

    private String postUrl(BbsPost post) {
        var slug = post.getSpec().getSlug();
        if (StringUtils.isBlank(slug)) {
            return null;
        }
        return externalLinkProcessor.processLink(BbsUrls.postPermalink(slug));
    }
}
