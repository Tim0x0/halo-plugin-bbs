package com.timxs.bbs.service;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;
import static run.halo.app.extension.index.query.Queries.isNull;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.util.ReactiveOptimisticUpdates;
import com.timxs.bbs.vo.BbsCommentAdminVo;
import com.timxs.bbs.vo.BbsReplyAdminVo;
import com.timxs.bbs.vo.CommentOwnerVo;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.extension.index.query.Condition;

/**
 * Console 评论管理服务：帖子列表评论列弹窗的数据通道。
 *
 * <p>与官方评论管理的关键差别：所有操作先按帖子收窄——先过版主管辖
 * （{@link BbsPostService#getRequiredInScope}），再逐条校验评论 / 回复确实挂在该帖上。
 * RBAC 子资源只能解析到 {@code bbsposts/comments}，区分不了具体归属，服务端校验才是边界。
 * 不属于该帖的评论一律 404（不泄露存在性）。</p>
 *
 * <p>{@code BbsLockedCommentFilter} 只拦核心评论路径，不覆盖本服务：锁定 / 回收的
 * 读放行（评论还在、仍可管理），回复创建则拒绝。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsCommentAdminService {

    private static final GroupVersionKind POST_GVK = GroupVersionKind.fromExtension(BbsPost.class);
    private static final int RETRY_TIMES = 5;

    /** 楼中楼按发表时间正序（与 {@code BbsQueryService.REPLY_SORT} 同口径）。 */
    private static final Sort REPLY_SORT = Sort.by(
            Sort.Order.asc("spec.creationTime"),
            Sort.Order.asc("metadata.name"));

    private final ReactiveExtensionClient client;
    private final BbsPostService postService;

    public BbsCommentAdminService(ReactiveExtensionClient client, BbsPostService postService) {
        this.client = client;
        this.postService = postService;
    }

    // ---------------- 评论 ----------------

    /**
     * 评论管理列表（含未审核与隐藏；仅排除删除中）。
     *
     * @param approved {@code true}/{@code false} 过滤；其余值视为全部
     * @param keyword  匹配 spec.raw（官方评论搜索同款索引）
     * @param owner    作者 username（按 {@code User#name} 等值）
     * @param sort     白名单：{@code metadata.creationTimestamp,asc|desc}，缺省 desc
     */
    public Mono<ListResult<BbsCommentAdminVo>> listComments(String postName, int page, int size,
            String approved, String keyword, String owner, String sort) {
        return postService.getRequiredInScope(postName)
                .flatMap(post -> client.listBy(Comment.class,
                        commentListOptions(postName, approved, keyword, owner),
                        PageRequestImpl.of(page, size, commentSort(sort))))
                .flatMap(result -> assembleComments(result.getItems())
                        .map(vos -> new ListResult<>(result.getPage(), result.getSize(),
                                result.getTotal(), vos)));
    }

    /** 通过 / 取消通过评论（官方口径：approved + approvedTime 成对写）。 */
    public Mono<Comment> setCommentApproved(String postName, String commentName,
            boolean approved) {
        return requireCommentInPost(postName, commentName)
                .then(updateWithRetry(Comment.class, commentName, comment -> {
                    comment.getSpec().setApproved(approved);
                    comment.getSpec().setApprovedTime(approved ? Instant.now() : null);
                }));
    }

    /** 删除评论（级联删回复由核心 finalizer 完成）。 */
    public Mono<Void> deleteComment(String postName, String commentName) {
        return requireCommentInPost(postName, commentName)
                .flatMap(client::delete)
                .then();
    }

    // ---------------- 回复 ----------------

    /** 回复管理列表（含未审核与隐藏；仅排除删除中）。 */
    public Mono<ListResult<BbsReplyAdminVo>> listReplies(String postName, String commentName,
            int page, int size) {
        return requireCommentInPost(postName, commentName)
                .then(client.listBy(Reply.class, replyListOptions(commentName),
                        PageRequestImpl.of(page, size, REPLY_SORT)))
                .flatMap(result -> assembleReplies(result.getItems())
                        .map(vos -> new ListResult<>(result.getPage(), result.getSize(),
                                result.getTotal(), vos)));
    }

    /** 通过该评论下全部未审核回复（官方「通过全部回复」），返回处理条数。 */
    public Mono<Integer> approveUnreviewedReplies(String postName, String commentName) {
        return requireCommentInPost(postName, commentName)
                .flatMapMany(comment -> client.listAll(Reply.class,
                        ListOptions.builder().fieldQuery(and(
                                equal("spec.commentName", commentName),
                                equal("spec.approved", false),
                                isNull("metadata.deletionTimestamp"))).build(),
                        Sort.unsorted()))
                .concatMap(reply -> updateWithRetry(Reply.class,
                        reply.getMetadata().getName(), latest -> {
                            latest.getSpec().setApproved(true);
                            latest.getSpec().setApprovedTime(Instant.now());
                        }))
                .count()
                .map(Long::intValue);
    }

    /** 通过 / 取消通过单条回复。 */
    public Mono<Reply> setReplyApproved(String postName, String commentName, String replyName,
            boolean approved) {
        return requireReplyInPost(postName, commentName, replyName)
                .then(updateWithRetry(Reply.class, replyName, reply -> {
                    reply.getSpec().setApproved(approved);
                    reply.getSpec().setApprovedTime(approved ? Instant.now() : null);
                }));
    }

    public Mono<Void> deleteReply(String postName, String commentName, String replyName) {
        return requireReplyInPost(postName, commentName, replyName)
                .flatMap(client::delete)
                .then();
    }

    /**
     * 版主以当前用户身份回复（对齐官方回复创建：管理端回复直接通过）。
     * 锁定 / 回收中的帖子禁止回复；引用回复必须真实挂在同一评论下。
     */
    public Mono<Reply> createReply(String postName, String commentName, String raw,
            String quoteReply, String actor) {
        var cleanRaw = StringUtils.trimToNull(raw);
        if (cleanRaw == null) {
            return Mono.error(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "回复内容不能为空"));
        }
        var quote = StringUtils.trimToNull(quoteReply);
        return postService.getRequiredInScope(postName)
                .flatMap(BbsCommentAdminService::requireReplyable)
                .then(requireCommentInPost(postName, commentName))
                .then(Mono.defer(() -> {
                    if (quote == null) {
                        return Mono.just(Boolean.TRUE);
                    }
                    return client.fetch(Reply.class, quote)
                            .filter(existing -> commentName.equals(
                                    existing.getSpec().getCommentName()))
                            .hasElement()
                            .flatMap(exists -> exists ? Mono.just(Boolean.TRUE)
                                    : Mono.error(new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST, "被引用的回复不存在")));
                }))
                .then(client.fetch(User.class, actor)
                        .map(user -> StringUtils.defaultIfBlank(
                                user.getSpec().getDisplayName(), actor))
                        .defaultIfEmpty(actor))
                .flatMap(displayName -> {
                    var reply = new Reply();
                    var metadata = new Metadata();
                    metadata.setName(UUID.randomUUID().toString());
                    reply.setMetadata(metadata);
                    var spec = new Reply.ReplySpec();
                    spec.setCommentName(commentName);
                    spec.setQuoteReply(quote);
                    spec.setRaw(cleanRaw);
                    spec.setContent(HtmlSanitizer.clean(cleanRaw));
                    var owner = new Comment.CommentOwner();
                    owner.setKind("User");
                    owner.setName(actor);
                    owner.setDisplayName(displayName);
                    spec.setOwner(owner);
                    spec.setApproved(true);
                    spec.setApprovedTime(Instant.now());
                    spec.setHidden(false);
                    spec.setTop(false);
                    spec.setPriority(0);
                    spec.setAllowNotification(true);
                    spec.setCreationTime(Instant.now());
                    reply.setSpec(spec);
                    return client.create(reply);
                });
    }

    // ---------------- 归属校验 ----------------

    /** 评论必须存在且挂在指定帖上；否则 404（不泄露其他主题的评论存在性）。 */
    private Mono<Comment> requireCommentInPost(String postName, String commentName) {
        return client.fetch(Comment.class, commentName)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "评论不存在")))
                .flatMap(comment -> belongsToPost(comment.getSpec() == null
                        ? null : comment.getSpec().getSubjectRef(), postName)
                                ? Mono.just(comment)
                                : Mono.error(new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "评论不存在")));
    }

    /** 回复必须存在且挂在指定评论下（评论归属先校验）。 */
    private Mono<Reply> requireReplyInPost(String postName, String commentName, String replyName) {
        return requireCommentInPost(postName, commentName)
                .then(client.fetch(Reply.class, replyName)
                        .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "回复不存在"))))
                .filter(reply -> reply.getSpec() != null
                        && commentName.equals(reply.getSpec().getCommentName()))
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "回复不存在")));
    }

    private static boolean belongsToPost(Ref ref, String postName) {
        return ref != null
                && POST_GVK.group().equals(ref.getGroup())
                && POST_GVK.kind().equals(ref.getKind())
                && postName.equals(ref.getName());
    }

    /** 可回复性：锁定禁回复；回收站帖禁回复。 */
    private static Mono<BbsPost> requireReplyable(BbsPost post) {
        if (Boolean.TRUE.equals(post.getSpec().getLocked())) {
            return Mono.error(() -> new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "帖子已锁定，禁止回复"));
        }
        if (Boolean.TRUE.equals(post.getSpec().getDeleted())
                || post.getMetadata().getDeletionTimestamp() != null) {
            return Mono.error(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "帖子在回收站中，不能回复"));
        }
        return Mono.just(post);
    }

    // ---------------- 查询与装配 ----------------

    private static ListOptions commentListOptions(String postName, String approved,
            String keyword, String owner) {
        Condition query = and(
                equal("spec.subjectRef", postSubjectRefKey(postName)),
                isNull("metadata.deletionTimestamp"));
        if ("true".equals(approved) || "false".equals(approved)) {
            query = and(query, equal("spec.approved", Boolean.parseBoolean(approved)));
        }
        if (StringUtils.isNotBlank(keyword)) {
            query = and(query, contains("spec.raw", keyword.trim()));
        }
        if (StringUtils.isNotBlank(owner)) {
            query = and(query, equal("spec.owner",
                    Comment.CommentOwner.ownerIdentity("User", owner.trim())));
        }
        return ListOptions.builder().fieldQuery(query).build();
    }

    private static Sort commentSort(String sort) {
        if ("metadata.creationTimestamp,asc".equals(sort)) {
            return Sort.by(Sort.Order.asc("metadata.creationTimestamp"),
                    Sort.Order.asc("metadata.name"));
        }
        // 缺省最新在前（对齐官方评论列表默认排序方向）
        return Sort.by(Sort.Order.desc("metadata.creationTimestamp"),
                Sort.Order.desc("metadata.name"));
    }

    private static ListOptions replyListOptions(String commentName) {
        return ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.commentName", commentName),
                        isNull("metadata.deletionTimestamp")))
                .build();
    }

    /** 批量装配评论：User owner 名一次 listAll 建 Map 再内联，避免 N+1。 */
    private Mono<List<BbsCommentAdminVo>> assembleComments(List<Comment> comments) {
        if (comments.isEmpty()) {
            return Mono.just(List.of());
        }
        var owners = comments.stream()
                .map(c -> c.getSpec() == null ? null : c.getSpec().getOwner())
                .toList();
        return fetchUsers(userOwnerNames(owners))
                .map(users -> comments.stream()
                        .map(comment -> toCommentVo(comment, users))
                        .toList());
    }

    private Mono<List<BbsReplyAdminVo>> assembleReplies(List<Reply> replies) {
        if (replies.isEmpty()) {
            return Mono.just(List.of());
        }
        var owners = replies.stream()
                .map(r -> r.getSpec() == null ? null : r.getSpec().getOwner())
                .toList();
        return fetchUsers(userOwnerNames(owners))
                .map(users -> replies.stream()
                        .map(reply -> toReplyVo(reply, users))
                        .toList());
    }

    private static Set<String> userOwnerNames(List<Comment.CommentOwner> owners) {
        var names = new HashSet<String>();
        for (var owner : owners) {
            if (owner != null && "User".equals(owner.getKind())
                    && StringUtils.isNotBlank(owner.getName())) {
                names.add(owner.getName());
            }
        }
        return names;
    }

    private Mono<Map<String, User>> fetchUsers(Set<String> names) {
        if (names.isEmpty()) {
            return Mono.just(Map.of());
        }
        var options = ListOptions.builder()
                .fieldQuery(in("metadata.name", names))
                .build();
        return client.listAll(User.class, options, Sort.unsorted())
                .collectMap(user -> user.getMetadata().getName());
    }

    private static BbsCommentAdminVo toCommentVo(Comment comment, Map<String, User> users) {
        var spec = comment.getSpec();
        var status = comment.getStatus();
        return BbsCommentAdminVo.builder()
                .name(comment.getMetadata().getName())
                .owner(CommentOwnerVo.from(spec == null ? null : spec.getOwner(), users))
                .content(spec == null ? null : spec.getContent())
                .approved(spec == null ? null : spec.getApproved())
                .hidden(spec == null ? null : spec.getHidden())
                .top(spec == null ? null : spec.getTop())
                .priority(spec == null ? null : spec.getPriority())
                .creationTime(spec != null && spec.getCreationTime() != null
                        ? spec.getCreationTime()
                        : comment.getMetadata().getCreationTimestamp())
                .approvedTime(spec == null ? null : spec.getApprovedTime())
                .replyCount(status != null && status.getReplyCount() != null
                        ? status.getReplyCount() : 0)
                .deleting(comment.getMetadata().getDeletionTimestamp() != null)
                .ipAddress(spec == null ? null : spec.getIpAddress())
                .userAgent(spec == null ? null : spec.getUserAgent())
                .build();
    }

    private static BbsReplyAdminVo toReplyVo(Reply reply, Map<String, User> users) {
        var spec = reply.getSpec();
        return BbsReplyAdminVo.builder()
                .name(reply.getMetadata().getName())
                .owner(CommentOwnerVo.from(spec == null ? null : spec.getOwner(), users))
                .content(spec == null ? null : spec.getContent())
                .approved(spec == null ? null : spec.getApproved())
                .hidden(spec == null ? null : spec.getHidden())
                .creationTime(spec != null && spec.getCreationTime() != null
                        ? spec.getCreationTime()
                        : reply.getMetadata().getCreationTimestamp())
                .approvedTime(spec == null ? null : spec.getApprovedTime())
                .deleting(reply.getMetadata().getDeletionTimestamp() != null)
                .commentName(spec == null ? null : spec.getCommentName())
                .quoteReply(spec == null ? null : spec.getQuoteReply())
                .build();
    }

    private static String postSubjectRefKey(String postName) {
        var ref = new Ref();
        ref.setGroup(POST_GVK.group());
        ref.setKind(POST_GVK.kind());
        ref.setName(postName);
        return Comment.toSubjectRefKey(ref);
    }

    /** 乐观锁重试：每次从重新 fetch 开始（拿新 version 再改），重试规格用共享工具。 */
    private <T extends Extension> Mono<T> updateWithRetry(Class<T> type, String name,
            Consumer<T> mutation) {
        return Mono.defer(() -> client.fetch(type, name)
                        .map(extension -> {
                            mutation.accept(extension);
                            return extension;
                        })
                        .flatMap(client::update))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_TIMES, Duration.ofMillis(50)));
    }
}
