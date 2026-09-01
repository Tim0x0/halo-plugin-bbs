package com.timxs.bbs.comment;

import com.timxs.bbs.extension.BbsPost;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.security.AdditionalWebFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BBS 评论门闩：Halo 创建评论 / 回复不看 {@code CommentSubject}，须在业务处理器前拦下
 * 未发布 / 回收站 / 已锁定帖子。
 *
 * <p>写入覆盖 Halo 公开评论端点与评论 Extension CRUD；读取覆盖 Halo 公共评论列表、
 * 单条评论与回复列表。Halo 不会为任意 Extension 自动生成 {@code comments} 子资源。
 * 已发布锁定帖的读取仍放行——锁定只禁止新增内容；草稿、撤回和回收站主体统一返回
 * 404，避免从通用评论 API 绕过帖子可见性。</p>
 *
 * <p>{@link #getOrder()} 的 {@code LOWEST_PRECEDENCE} 仅表示在 AdditionalWebFilter
 * 阶段末位执行，仍在实际评论处理器之前。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsLockedCommentFilter implements AdditionalWebFilter {

    private static final GroupVersionKind POST_GVK = GroupVersionKind.fromExtension(BbsPost.class);

    private static final String PUBLIC_COMMENTS = "/apis/api.halo.run/v1alpha1/comments";
    private static final String CRUD_COMMENTS = "/apis/content.halo.run/v1alpha1/comments";
    private static final String CRUD_REPLIES = "/apis/content.halo.run/v1alpha1/replies";
    private static final JsonMapper JSON = new JsonMapper();

    // 过滤器一次性聚合请求体的内存上限，对齐 Halo 官方默认的 codec 上限
    private static final int MAX_REQUEST_BODY_BYTES =
            (int) DataSize.ofMegabytes(10).toBytes();

    private final ReactiveExtensionClient client;

    public BbsLockedCommentFilter(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var path = request.getPath().pathWithinApplication().value();
        if (request.getMethod() == HttpMethod.GET) {
            return requireReadableRequest(request, path)
                    .then(Mono.defer(() -> chain.filter(exchange)));
        }
        if (request.getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        if (isPublicCommentCreate(path) || isCrudCommentCreate(path)) {
            return interceptBody(exchange, chain, this::rejectIfLockedSubject);
        }
        if (isPublicReplyCreate(path)) {
            var commentName = commentNameFromReplyPath(path);
            return rejectIfLockedComment(commentName)
                    .then(Mono.defer(() -> chain.filter(exchange)));
        }
        if (isCrudReplyCreate(path)) {
            return interceptBody(exchange, chain, this::rejectIfLockedReplyBody);
        }
        return chain.filter(exchange);
    }

    private boolean isPublicCommentCreate(String path) {
        return PUBLIC_COMMENTS.equals(path);
    }

    private boolean isPublicReplyCreate(String path) {
        return commentNameFromReplyPath(path) != null;
    }

    private boolean isCrudCommentCreate(String path) {
        return CRUD_COMMENTS.equals(path);
    }

    private boolean isCrudReplyCreate(String path) {
        return CRUD_REPLIES.equals(path);
    }

    private static String commentNameFromReplyPath(String path) {
        if (!path.startsWith(PUBLIC_COMMENTS + "/") || !path.endsWith("/reply")) {
            return null;
        }
        var prefix = PUBLIC_COMMENTS + "/";
        var name = path.substring(prefix.length(), path.length() - "/reply".length());
        return validPathName(name);
    }

    /**
     * Halo 公共评论查询只按评论自身状态过滤，不会联动检查 CommentSubject 是否仍公开。
     * 这里仅补 BbsPost 主体边界，其他 Halo / 插件评论主体完全透传。
     */
    private Mono<Void> requireReadableRequest(ServerHttpRequest request, String path) {
        if (PUBLIC_COMMENTS.equals(path)) {
            var params = request.getQueryParams();
            if (!isBbsPost(params.getFirst("group"), params.getFirst("kind"))) {
                return Mono.empty();
            }
            var postName = StringUtils.trimToNull(params.getFirst("name"));
            // 缺少 name 的请求交给 Halo 自身返回 400，不在过滤器里改变参数校验语义。
            return postName == null ? Mono.empty() : requirePublicPost(postName).then();
        }
        var commentName = commentNameFromPublicReadPath(path);
        return commentName == null ? Mono.empty() : requirePublicCommentSubject(commentName);
    }

    /** GET /comments/{name} 与 GET /comments/{name}/reply。 */
    private static String commentNameFromPublicReadPath(String path) {
        var prefix = PUBLIC_COMMENTS + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        var name = path.substring(prefix.length());
        if (name.endsWith("/reply")) {
            name = name.substring(0, name.length() - "/reply".length());
        }
        return validPathName(name);
    }

    private static String validPathName(String name) {
        return StringUtils.isBlank(name) || name.contains("/")
                ? null : name;
    }

    private Mono<Void> requirePublicCommentSubject(String commentName) {
        return client.fetch(Comment.class, commentName)
                .flatMap(comment -> {
                    var ref = comment.getSpec() == null ? null : comment.getSpec().getSubjectRef();
                    if (ref == null || !isBbsPost(ref.getGroup(), ref.getKind())) {
                        return Mono.empty();
                    }
                    return requirePublicPost(ref.getName()).then();
                });
    }

    private Mono<Void> interceptBody(ServerWebExchange exchange, WebFilterChain chain,
            java.util.function.Function<byte[], Mono<Void>> check) {
        var contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            return bodyTooLarge();
        }
        // 此处只约束过滤器一次性聚合的内存，不限制评论文字长度。
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_REQUEST_BODY_BYTES)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, error ->
                        new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                                "评论请求体超过 Halo 允许的大小", error))
                .flatMap(bytes -> check.apply(bytes).then(Mono.defer(() ->
                        chain.filter(exchange.mutate().request(replay(exchange, bytes)).build()))));
    }

    private static <T> Mono<T> bodyTooLarge() {
        return Mono.error(new ResponseStatusException(
                HttpStatus.CONTENT_TOO_LARGE, "评论请求体超过 Halo 允许的大小"));
    }

    private static ServerHttpRequest replay(ServerWebExchange exchange, byte[] bytes) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
            }
        };
    }

    private Mono<Void> rejectIfLockedSubject(byte[] body) {
        var postName = postNameFromCommentBody(body);
        if (StringUtils.isBlank(postName)) {
            return Mono.empty();
        }
        return rejectIfLockedPost(postName);
    }

    private Mono<Void> rejectIfLockedReplyBody(byte[] body) {
        var commentName = commentNameFromReplyBody(body);
        if (StringUtils.isBlank(commentName)) {
            return Mono.empty();
        }
        return rejectIfLockedComment(commentName);
    }

    private Mono<Void> rejectIfLockedComment(String commentName) {
        if (StringUtils.isBlank(commentName)) {
            return Mono.empty();
        }
        return client.fetch(Comment.class, commentName)
                .flatMap(comment -> {
                    var ref = comment.getSpec() == null ? null : comment.getSpec().getSubjectRef();
                    if (ref == null || !isBbsPost(ref.getGroup(), ref.getKind())) {
                        return Mono.empty();
                    }
                    return rejectIfLockedPost(ref.getName());
                });
    }

    private Mono<Void> rejectIfLockedPost(String postName) {
        return requirePublicPost(postName)
                .flatMap(post -> Boolean.TRUE.equals(post.getSpec().getLocked())
                        ? Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "帖子已被锁定，无法评论"))
                        : Mono.empty());
    }

    /** 公开评论主体必须仍是已发布、未回收、未进入删除流程的帖子。 */
    private Mono<BbsPost> requirePublicPost(String postName) {
        if (StringUtils.isBlank(postName)) {
            return publicPostNotFound();
        }
        return client.fetch(BbsPost.class, postName)
                .filter(post -> post.getMetadata() != null
                        && post.getMetadata().getDeletionTimestamp() == null
                        && post.getSpec() != null
                        && post.getSpec().getPhase() == BbsPost.Phase.PUBLISHED
                        && !Boolean.TRUE.equals(post.getSpec().getDeleted()))
                .switchIfEmpty(Mono.defer(BbsLockedCommentFilter::publicPostNotFound));
    }

    private static <T> Mono<T> publicPostNotFound() {
        return Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "帖子不存在或尚未发布"));
    }

    private String postNameFromCommentBody(byte[] body) {
        var node = readJson(body);
        if (node == null) {
            return null;
        }
        var ref = node.path("subjectRef");
        if (ref.isMissingNode() || ref.isNull()) {
            ref = node.path("spec").path("subjectRef");
        }
        if (!isBbsPost(text(ref, "group"), text(ref, "kind"))) {
            return null;
        }
        return StringUtils.trimToNull(text(ref, "name"));
    }

    private String commentNameFromReplyBody(byte[] body) {
        var node = readJson(body);
        if (node == null) {
            return null;
        }
        var name = text(node, "commentName");
        if (StringUtils.isBlank(name)) {
            name = text(node.path("spec"), "commentName");
        }
        return StringUtils.trimToNull(name);
    }

    private static boolean isBbsPost(String group, String kind) {
        return POST_GVK.group().equals(group) && POST_GVK.kind().equals(kind);
    }

    private static JsonNode readJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            var node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
            // Jackson 3 对空白内容返回 MissingNode 而非报错，统一归一到 null
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
