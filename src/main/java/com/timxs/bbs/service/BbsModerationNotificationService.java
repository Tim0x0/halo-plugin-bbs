package com.timxs.bbs.service;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.util.BbsUrls;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.ReasonAttributes;
import run.halo.app.notification.UserIdentity;

/**
 * 审核结果通知：走 Halo 官方通知中心（2.10+，当前基线 2.25 可用）。
 *
 * <p>订阅对齐官方文章评论通知：按 {@code props.owner} 匹配作者，提交前调和器
 * 即订阅，用户可在 UC 通知偏好里开关。触发时再订阅一次（幂等），避免调和尚未
 * 跑完就审核通过而漏通知。</p>
 *
 * <p>作者审核自己的帖子不发（对齐官方「自己评自己的文章不通知」）。</p>
 */
@Component
@Slf4j
public class BbsModerationNotificationService {

    public static final String APPROVED = "bbs-post-approved";
    public static final String REJECTED = "bbs-post-rejected";

    private final NotificationCenter notificationCenter;
    private final NotificationReasonEmitter reasonEmitter;
    private final ExternalLinkProcessor externalLinkProcessor;

    public BbsModerationNotificationService(NotificationCenter notificationCenter,
            NotificationReasonEmitter reasonEmitter,
            ExternalLinkProcessor externalLinkProcessor) {
        this.notificationCenter = notificationCenter;
        this.reasonEmitter = reasonEmitter;
        this.externalLinkProcessor = externalLinkProcessor;
    }

    /** 为帖子作者订阅两类审核结果；无作者则跳过。订阅本身幂等。 */
    public Mono<Void> subscribe(BbsPost post) {
        var owner = post.getSpec() == null ? null : post.getSpec().getOwner();
        if (StringUtils.isBlank(owner)) {
            return Mono.empty();
        }
        return subscribe(owner, APPROVED).then(subscribe(owner, REJECTED));
    }

    public Mono<Void> notifyApproved(BbsPost post, String actor) {
        return emit(post, actor, APPROVED, null);
    }

    public Mono<Void> notifyRejected(BbsPost post, String actor, String reason) {
        return emit(post, actor, REJECTED, StringUtils.defaultIfBlank(
                StringUtils.trimToNull(reason), "未填写原因"));
    }

    private Mono<Void> emit(BbsPost post, String actor, String reasonType, String rejectReason) {
        var spec = post.getSpec();
        if (spec == null || StringUtils.isBlank(spec.getOwner())) {
            return Mono.empty();
        }
        var owner = spec.getOwner();
        // 自己审自己：不打扰（版主发帖走审核、又自己通过的场景）
        if (StringUtils.equals(owner, actor)) {
            return Mono.empty();
        }
        var title = StringUtils.defaultIfBlank(workingTitle(spec), post.getMetadata().getName());
        var url = externalLinkProcessor.processLink(resultUrl(post));
        var attributes = new ReasonAttributes();
        attributes.put("owner", owner);
        attributes.put("postName", post.getMetadata().getName());
        attributes.put("postTitle", title);
        attributes.put("postUrl", url);
        if (rejectReason != null) {
            attributes.put("rejectReason", rejectReason);
        }
        var subject = Reason.Subject.builder()
                .apiVersion(post.getApiVersion())
                .kind(post.getKind())
                .name(post.getMetadata().getName())
                .title(title)
                .url(url)
                .build();
        return subscribe(post)
                .then(reasonEmitter.emit(reasonType, builder -> builder
                        .attributes(attributes)
                        .author(UserIdentity.of(StringUtils.defaultIfBlank(actor, owner)))
                        .subject(subject)))
                .onErrorResume(error -> {
                    log.warn("发送 BBS 审核通知失败：post={} type={}",
                            post.getMetadata().getName(), reasonType, error);
                    return Mono.empty();
                });
    }

    private Mono<Void> subscribe(String owner, String reasonType) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(owner);
        var reason = new Subscription.InterestReason();
        reason.setReasonType(reasonType);
        reason.setExpression("props.owner == '%s'".formatted(owner.replace("'", "\\'")));
        return notificationCenter.subscribe(subscriber, reason).then()
                .onErrorResume(error -> {
                    log.warn("订阅 BBS 审核通知失败：owner={} type={}", owner, reasonType, error);
                    return Mono.empty();
                });
    }

    private static String workingTitle(BbsPost.Spec spec) {
        var draft = spec.getDraft();
        if (draft != null && StringUtils.isNotBlank(draft.getTitle())) {
            return draft.getTitle();
        }
        return spec.getTitle();
    }

    /**
     * 通过后链到前台帖子。驳回一律链到 UC 编辑器：未发布帖前台打不开；
     * 已发布帖的修改稿被驳回时前台仍是已发布版本，作者必须进编辑器改工作稿。
     */
    private static String resultUrl(BbsPost post) {
        var spec = post.getSpec();
        if (spec.getPhase() == BbsPost.Phase.PUBLISHED
                && !hasRejectedDraft(spec)
                && StringUtils.isNotBlank(spec.getSlug())) {
            return BbsUrls.postPermalink(spec.getSlug());
        }
        return "/uc/bbs/editor?name=" + post.getMetadata().getName();
    }

    private static boolean hasRejectedDraft(BbsPost.Spec spec) {
        return spec.getDraft() != null
                && spec.getDraft().getPhase() == BbsPost.Phase.REJECTED;
    }
}
