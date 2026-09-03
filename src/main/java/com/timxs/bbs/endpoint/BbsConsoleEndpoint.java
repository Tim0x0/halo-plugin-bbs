package com.timxs.bbs.endpoint;

import static com.timxs.bbs.util.BbsEndpointParams.commentNameParam;
import static com.timxs.bbs.util.BbsEndpointParams.currentUsername;
import static com.timxs.bbs.util.BbsEndpointParams.nameParam;
import static com.timxs.bbs.util.BbsEndpointParams.optionalSnapshotNameParam;
import static com.timxs.bbs.util.BbsEndpointParams.pageParam;
import static com.timxs.bbs.util.BbsEndpointParams.queryParam;
import static com.timxs.bbs.util.BbsEndpointParams.replyNameParam;
import static com.timxs.bbs.util.BbsEndpointParams.requiredQuery;
import static com.timxs.bbs.util.BbsEndpointParams.sizeParam;
import static com.timxs.bbs.util.BbsEndpointParams.snapshotNameParam;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.query.BbsQueryService;
import com.timxs.bbs.service.BbsCategoryService;
import com.timxs.bbs.service.BbsCommentAdminService;
import com.timxs.bbs.service.BbsModerationScope;
import com.timxs.bbs.service.BbsPostService;
import com.timxs.bbs.service.ContentUpdateParam;
import com.timxs.bbs.service.PostRequest;
import com.timxs.bbs.service.ReplyCreateParam;
import com.timxs.bbs.service.RevertSnapshotParam;
import com.timxs.bbs.util.BbsPageRequests;
import com.timxs.bbs.vo.BbsCommentAdminVo;
import com.timxs.bbs.vo.BbsContentVo;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.BbsReplyAdminVo;
import com.timxs.bbs.vo.BbsSnapshotDto;
import com.timxs.bbs.vo.CategoryVo;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;

/**
 * Console（后台）管理接口：帖子列表（服务端筛选）、创建 / 更新 / 发布 / 置顶 / 删除、
 * 分类带计数列表。分类的增删改走 Halo 自动生成的 CRUD API。
 *
 * @author Tim0x0
 */
@Component
@RequiredArgsConstructor
public class BbsConsoleEndpoint implements CustomEndpoint {

    private static final String TAG = "BbsV1alpha1Console";

    private final BbsPostService postService;
    private final BbsCategoryService categoryService;
    private final BbsQueryService queryService;
    private final BbsModerationScope moderationScope;
    private final BbsCommentAdminService commentAdminService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/bbsposts", this::listPosts, builder -> builder
                        .operationId("ListBbsPostsConsole").tag(TAG)
                        .description("帖子管理列表（keyword/categoryName/type/phase/owner 筛选；"
                                + "sort 白名单：creationTimestamp|publishTime|lastActivityTime"
                                + "|lastEditTime|commentsCount,asc|desc）")
                        .parameter(pageParam()).parameter(sizeParam())
                        .parameter(queryParam("keyword")).parameter(queryParam("categoryName"))
                        .parameter(queryParam("type")).parameter(queryParam("phase"))
                        .parameter(queryParam("sort")).parameter(queryParam("owner"))
                        .parameter(queryParam("deleted", Boolean.class))
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(BbsPostVo.class))))
                .POST("/bbsposts", this::createPost, builder -> builder
                        .operationId("CreateBbsPostConsole").tag(TAG)
                        .description("后台创建帖子/公告（publish=true 直接发布）")
                        .parameter(queryParam("publish", Boolean.class))
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                // 字面量路由必须先于通配 /{name} 注册，否则 slug-taken 会被当成帖子名
                .GET("/bbsposts/slug-taken", this::isSlugTaken, builder -> builder
                        .operationId("CheckBbsPostSlugTakenConsole").tag(TAG)
                        .description("别名占用预检：是否被发布中内容占用"
                                + "（已发布 / 待审核 / 已提交修改稿；草稿与回收站不占）；"
                                + "excludeName 排除自己")
                        .parameter(queryParam("slug"))
                        .parameter(queryParam("excludeName"))
                        .response(responseBuilder().implementation(Boolean.class)))
                .GET("/bbsposts/{name}", this::getPost, builder -> builder
                        .operationId("GetBbsPostConsole").tag(TAG)
                        .description("取单篇帖子编辑详情（有工作草稿时返回工作稿正文）")
                        .parameter(nameParam())
                        .response(responseBuilder().implementation(BbsPostVo.class)))
                .GET("/bbsposts/{name}/head-content", this::getHeadContent,
                        builder -> builder.operationId("GetBbsPostHeadContentConsole").tag(TAG)
                                .description("取编辑器工作版本正文")
                                .parameter(nameParam())
                                .response(responseBuilder()
                                        .implementation(BbsContentVo.class)))
                .GET("/bbsposts/{name}/release-content", this::getReleaseContent,
                        builder -> builder.operationId("GetBbsPostReleaseContentConsole").tag(TAG)
                                .description("取前台发布版本正文")
                                .parameter(nameParam())
                                .response(responseBuilder()
                                        .implementation(BbsContentVo.class)))
                .GET("/bbsposts/{name}/content", this::getContent,
                        builder -> builder.operationId("GetBbsPostContentConsole").tag(TAG)
                                .description("还原指定快照的正文（snapshotName 缺省取 head）")
                                .parameter(nameParam()).parameter(optionalSnapshotNameParam())
                                .response(responseBuilder()
                                        .implementation(BbsContentVo.class)))
                .GET("/bbsposts/{name}/snapshot", this::listSnapshots,
                        builder -> builder.operationId("ListBbsPostSnapshotsConsole").tag(TAG)
                                .description("列出帖子完整快照历史（不含正文）")
                                .parameter(nameParam())
                                .response(responseBuilder()
                                        .implementationArray(BbsSnapshotDto.class)))
                .PUT("/bbsposts/{name}/content", this::saveContent,
                        builder -> builder.operationId("UpdateBbsPostContentConsole").tag(TAG)
                                .description("只保存正文；version 与服务端 head 不一致时分叉新快照")
                                .parameter(nameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(ContentUpdateParam.class))))
                                .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}/revert-content", this::revertContent,
                        builder -> builder.operationId("RevertBbsPostContentConsole").tag(TAG)
                                .description("恢复历史版本：以旧内容新建快照成为 head，"
                                        + "已发布帖按审核策略重新发布或进入待审核")
                                .parameter(nameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(RevertSnapshotParam.class)))))
                .DELETE("/bbsposts/{name}/content", this::deleteContent,
                        builder -> builder.operationId("DeleteBbsPostContentConsole").tag(TAG)
                                .description("删除快照；基线与发布版不可删，删 head 会先回退")
                                .parameter(nameParam()).parameter(snapshotNameParam()))
                .GET("/bbsposts/{name}/moderation-records", this::listModerationRecords,
                        builder -> builder.operationId("ListBbsModerationRecordsConsole").tag(TAG)
                                .description("列出该帖提交、通过、驳回等只追加审计记录")
                                .parameter(nameParam()))
                .GET("/bbsposts/{name}/comments", this::listPostComments,
                        builder -> builder.operationId("ListBbsPostCommentsConsole").tag(TAG)
                                .description("帖子评论管理列表（含未审核与隐藏；"
                                        + "approved 过滤 true/false，keyword 匹配 raw，"
                                        + "owner 按作者 username；sort 白名单："
                                        + "metadata.creationTimestamp,asc|desc）")
                                .parameter(nameParam()).parameter(pageParam()).parameter(sizeParam())
                                .parameter(queryParam("approved")).parameter(queryParam("keyword"))
                                .parameter(queryParam("owner")).parameter(queryParam("sort"))
                                .response(responseBuilder().implementation(
                                        ListResult.generateGenericClass(BbsCommentAdminVo.class))))
                .PUT("/bbsposts/{name}/comments/{commentName}/approve", this::approveComment,
                        builder -> builder.operationId("ApproveBbsPostComment").tag(TAG)
                                .description("通过评论")
                                .parameter(nameParam()).parameter(commentNameParam()))
                .PUT("/bbsposts/{name}/comments/{commentName}/unapprove", this::unapproveComment,
                        builder -> builder.operationId("UnapproveBbsPostComment").tag(TAG)
                                .description("取消通过评论")
                                .parameter(nameParam()).parameter(commentNameParam()))
                .DELETE("/bbsposts/{name}/comments/{commentName}", this::deleteComment,
                        builder -> builder.operationId("DeleteBbsPostComment").tag(TAG)
                                .description("删除评论（级联删回复）")
                                .parameter(nameParam()).parameter(commentNameParam()))
                .GET("/bbsposts/{name}/comments/{commentName}/replies", this::listCommentReplies,
                        builder -> builder.operationId("ListBbsPostCommentReplies").tag(TAG)
                                .description("评论的回复管理列表（含未审核与隐藏）")
                                .parameter(nameParam()).parameter(commentNameParam())
                                .parameter(pageParam()).parameter(sizeParam())
                                .response(responseBuilder().implementation(
                                        ListResult.generateGenericClass(BbsReplyAdminVo.class))))
                .PUT("/bbsposts/{name}/comments/{commentName}/replies/approve-unreviewed",
                        this::approveUnreviewedReplies,
                        builder -> builder.operationId("ApproveBbsPostCommentReplies").tag(TAG)
                                .description("通过该评论下全部未审核回复")
                                .parameter(nameParam()).parameter(commentNameParam()))
                .PUT("/bbsposts/{name}/comments/{commentName}/replies/{replyName}/approve",
                        this::approveReply,
                        builder -> builder.operationId("ApproveBbsPostReply").tag(TAG)
                                .description("通过回复")
                                .parameter(nameParam()).parameter(commentNameParam())
                                .parameter(replyNameParam()))
                .PUT("/bbsposts/{name}/comments/{commentName}/replies/{replyName}/unapprove",
                        this::unapproveReply,
                        builder -> builder.operationId("UnapproveBbsPostReply").tag(TAG)
                                .description("取消通过回复")
                                .parameter(nameParam()).parameter(commentNameParam())
                                .parameter(replyNameParam()))
                .DELETE("/bbsposts/{name}/comments/{commentName}/replies/{replyName}",
                        this::deleteReply,
                        builder -> builder.operationId("DeleteBbsPostReply").tag(TAG)
                                .description("删除回复")
                                .parameter(nameParam()).parameter(commentNameParam())
                                .parameter(replyNameParam()))
                .POST("/bbsposts/{name}/comments/{commentName}/replies", this::createReply,
                        builder -> builder.operationId("CreateBbsPostReply").tag(TAG)
                                .description("版主以当前用户身份回复（直接通过；锁定 / 回收站帖禁止）")
                                .parameter(nameParam()).parameter(commentNameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(ReplyCreateParam.class))))
                                .response(responseBuilder().implementation(Reply.class)))
                .PUT("/bbsposts/{name}", this::updatePost, builder -> builder
                        .operationId("UpdateBbsPostConsole").tag(TAG)
                        .description("后台保存帖子（已发布帖写入工作稿；publish 子资源才提升为前台版本）")
                        .parameter(nameParam())
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}/publish",
                        req -> ok(postService.publish(req.pathVariable("name"))),
                        builder -> builder.operationId("PublishBbsPost").tag(TAG)
                                .description("发布").parameter(nameParam()))
                .PUT("/bbsposts/{name}/unpublish",
                        req -> ok(postService.unpublish(req.pathVariable("name"))),
                        builder -> builder.operationId("UnpublishBbsPost").tag(TAG)
                                .description("取消发布（回到未发布）").parameter(nameParam()))
                .PUT("/bbsposts/{name}/approve",
                        req -> ok(postService.approve(req.pathVariable("name"))),
                        builder -> builder.operationId("ApproveBbsPost").tag(TAG)
                                .description("审核通过并发布").parameter(nameParam()))
                .PUT("/bbsposts/{name}/reject", this::rejectPost,
                        builder -> builder.operationId("RejectBbsPost").tag(TAG)
                                .description("审核驳回（可附驳回原因，展示给作者）")
                                .parameter(nameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(RejectRequest.class)))))
                .PUT("/bbsposts/{name}/withdraw",
                        req -> ok(postService.withdrawInScope(req.pathVariable("name"))),
                        builder -> builder.operationId("WithdrawBbsPost").tag(TAG)
                                .description("取消提交（仅待审核；版主可代作者撤回）")
                                .parameter(nameParam()))
                .PUT("/bbsposts/{name}/pin",
                        req -> ok(postService.pin(req.pathVariable("name"))),
                        builder -> builder.operationId("PinBbsPost").tag(TAG)
                                .description("置顶").parameter(nameParam()))
                .PUT("/bbsposts/{name}/unpin",
                        req -> ok(postService.unpin(req.pathVariable("name"))),
                        builder -> builder.operationId("UnpinBbsPost").tag(TAG)
                                .description("取消置顶").parameter(nameParam()))
                .PUT("/bbsposts/{name}/lock",
                        req -> ok(postService.lock(req.pathVariable("name"))),
                        builder -> builder.operationId("LockBbsPost").tag(TAG)
                                .description("锁定（禁评论、禁作者编辑与删除）")
                                .parameter(nameParam()))
                .PUT("/bbsposts/{name}/unlock",
                        req -> ok(postService.unlock(req.pathVariable("name"))),
                        builder -> builder.operationId("UnlockBbsPost").tag(TAG)
                                .description("解锁").parameter(nameParam()))
                .PUT("/bbsposts/{name}/solve",
                        req -> ok(postService.setSolved(req.pathVariable("name"), true)),
                        builder -> builder.operationId("SolveBbsPost").tag(TAG)
                                .description("标记已解决（仅问答帖）").parameter(nameParam()))
                .PUT("/bbsposts/{name}/unsolve",
                        req -> ok(postService.setSolved(req.pathVariable("name"), false)),
                        builder -> builder.operationId("UnsolveBbsPost").tag(TAG)
                                .description("取消已解决（仅问答帖）").parameter(nameParam()))
                .DELETE("/bbsposts/{name}",
                        req -> postService.recycleInScope(req.pathVariable("name"))
                                .then(ServerResponse.ok().build()),
                        builder -> builder.operationId("RecycleBbsPostConsole").tag(TAG)
                                .description("移入回收站（软删除，可恢复）")
                                .parameter(nameParam()))
                .PUT("/bbsposts/{name}/restore",
                        req -> ok(postService.restore(req.pathVariable("name"))),
                        builder -> builder.operationId("RestoreBbsPost").tag(TAG)
                                .description("从回收站恢复").parameter(nameParam()))
                .DELETE("/bbsposts/{name}/permanently",
                        req -> postService.deleteInScope(req.pathVariable("name"))
                                .then(ServerResponse.ok().build()),
                        builder -> builder.operationId("DeleteBbsPostPermanently").tag(TAG)
                                .description("彻底删除（不可恢复）").parameter(nameParam()))
                .GET("/bbscategories", this::listCategories, builder -> builder
                        .operationId("ListBbsCategoriesConsole").tag(TAG)
                        .description("分类管理列表（含已发布帖子数，priority 升序）")
                        .response(responseBuilder().implementationArray(CategoryVo.class)))
                .PUT("/bbscategories/{name}/position", this::moveCategory, builder -> builder
                        .operationId("MoveBbsCategory").tag(TAG)
                        .description("移动分类位置：parentName 空=移到根成为一级分类，"
                                + "beforeName 空=追加到同级末尾；服务端重排受影响的同级序列")
                        .parameter(nameParam())
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder()
                                        .implementation(CategoryPositionRequest.class))))
                        .response(responseBuilder().implementationArray(CategoryVo.class)))
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.bbs.timxs.com", "v1alpha1");
    }

    private Mono<ServerResponse> listPosts(ServerRequest request) {
        // 管辖过滤：分区版主只看得到自己管的分类树，全站版主 / 管理角色不受限
        return currentUsername()
                .flatMap(moderationScope::visibleCategoryNames)
                .flatMap(scoped -> queryService.listConsole(
                        BbsPageRequests.page(request, 1),
                        BbsPageRequests.size(request, 20, BbsPageRequests.MAX_CONSOLE),
                        request.queryParam("keyword").orElse(null),
                        request.queryParam("categoryName").orElse(null),
                        request.queryParam("type").orElse(null),
                        request.queryParam("phase").orElse(null),
                        request.queryParam("sort").orElse(null),
                        request.queryParam("owner").orElse(null),
                        request.queryParam("deleted").map(Boolean::parseBoolean).orElse(false),
                        scoped))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> createPost(ServerRequest request) {
        boolean publish = request.queryParam("publish")
                .map(Boolean::parseBoolean).orElse(false);
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.create(tuple.getT1(), tuple.getT2(), true, publish))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    /** 别名占用预检：只回布尔，不暴露占用者是谁（官方同款只判存在性）。 */
    private Mono<ServerResponse> isSlugTaken(ServerRequest request) {
        return postService.isSlugTaken(
                        request.queryParam("slug").orElse(""),
                        request.queryParam("excludeName").orElse(null))
                .flatMap(taken -> ServerResponse.ok().bodyValue(taken));
    }

    private Mono<ServerResponse> getPost(ServerRequest request) {
        return postService.getRequiredInScope(request.pathVariable("name"))
                .flatMap(queryService::assembleEditingDetail)
                .flatMap(vo -> ServerResponse.ok().bodyValue(vo));
    }

    private Mono<ServerResponse> updatePost(ServerRequest request) {
        return request.bodyToMono(PostRequest.class)
                .flatMap(body -> postService.updateManaged(
                        request.pathVariable("name"), body))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> getHeadContent(ServerRequest request) {
        return postService.getHeadContentInScope(request.pathVariable("name"))
                .flatMap(content -> ServerResponse.ok().bodyValue(content));
    }

    private Mono<ServerResponse> getReleaseContent(ServerRequest request) {
        return postService.getReleaseContentInScope(request.pathVariable("name"))
                .flatMap(content -> ServerResponse.ok().bodyValue(content));
    }

    private Mono<ServerResponse> getContent(ServerRequest request) {
        return postService.getContentInScope(request.pathVariable("name"),
                        request.queryParam("snapshotName").orElse(null))
                .flatMap(content -> ServerResponse.ok().bodyValue(content));
    }

    private Mono<ServerResponse> listSnapshots(ServerRequest request) {
        return postService.listSnapshotsInScope(request.pathVariable("name"))
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> saveContent(ServerRequest request) {
        return request.bodyToMono(ContentUpdateParam.class)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "请提交正文")))
                .flatMap(body -> postService.saveContentInScope(
                        request.pathVariable("name"), body))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> revertContent(ServerRequest request) {
        return request.bodyToMono(RevertSnapshotParam.class)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "请指定快照")))
                .flatMap(body -> postService.revertContentInScope(
                        request.pathVariable("name"), body.snapshotName()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> deleteContent(ServerRequest request) {
        return postService.deleteContentInScope(request.pathVariable("name"),
                        requiredQuery(request, "snapshotName"))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> listModerationRecords(ServerRequest request) {
        return postService.listModerationRecordsInScope(request.pathVariable("name"))
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    // ---------------- 评论管理（列表评论列弹窗的数据通道） ----------------

    private Mono<ServerResponse> listPostComments(ServerRequest request) {
        return commentAdminService.listComments(
                        request.pathVariable("name"),
                        BbsPageRequests.page(request, 1),
                        BbsPageRequests.size(request, 20, BbsPageRequests.MAX_CONSOLE),
                        request.queryParam("approved").orElse(null),
                        request.queryParam("keyword").orElse(null),
                        request.queryParam("owner").orElse(null),
                        request.queryParam("sort").orElse(null))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> approveComment(ServerRequest request) {
        return ok(commentAdminService.setCommentApproved(
                request.pathVariable("name"), request.pathVariable("commentName"), true));
    }

    private Mono<ServerResponse> unapproveComment(ServerRequest request) {
        return ok(commentAdminService.setCommentApproved(
                request.pathVariable("name"), request.pathVariable("commentName"), false));
    }

    private Mono<ServerResponse> deleteComment(ServerRequest request) {
        return commentAdminService.deleteComment(
                        request.pathVariable("name"), request.pathVariable("commentName"))
                .then(ServerResponse.ok().build());
    }

    private Mono<ServerResponse> listCommentReplies(ServerRequest request) {
        return commentAdminService.listReplies(
                        request.pathVariable("name"),
                        request.pathVariable("commentName"),
                        BbsPageRequests.page(request, 1),
                        BbsPageRequests.size(request, 20, BbsPageRequests.MAX_CONSOLE))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> approveUnreviewedReplies(ServerRequest request) {
        return commentAdminService.approveUnreviewedReplies(
                        request.pathVariable("name"), request.pathVariable("commentName"))
                .flatMap(count -> ServerResponse.ok()
                        .bodyValue(Map.of("approvedCount", count)));
    }

    private Mono<ServerResponse> approveReply(ServerRequest request) {
        return ok(commentAdminService.setReplyApproved(
                request.pathVariable("name"), request.pathVariable("commentName"),
                request.pathVariable("replyName"), true));
    }

    private Mono<ServerResponse> unapproveReply(ServerRequest request) {
        return ok(commentAdminService.setReplyApproved(
                request.pathVariable("name"), request.pathVariable("commentName"),
                request.pathVariable("replyName"), false));
    }

    private Mono<ServerResponse> deleteReply(ServerRequest request) {
        return commentAdminService.deleteReply(
                        request.pathVariable("name"), request.pathVariable("commentName"),
                        request.pathVariable("replyName"))
                .then(ServerResponse.ok().build());
    }

    private Mono<ServerResponse> createReply(ServerRequest request) {
        return Mono.zip(request.bodyToMono(ReplyCreateParam.class)
                        .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "请提交回复内容"))),
                        currentUsername())
                .flatMap(tuple -> commentAdminService.createReply(
                        request.pathVariable("name"),
                        request.pathVariable("commentName"),
                        tuple.getT1().raw(), tuple.getT1().quoteReply(), tuple.getT2()))
                .flatMap(reply -> ServerResponse.ok().bodyValue(reply));
    }

    private Mono<ServerResponse> rejectPost(ServerRequest request) {
        return request.bodyToMono(RejectRequest.class)
                .defaultIfEmpty(new RejectRequest(null))
                .flatMap(body -> postService.reject(request.pathVariable("name"), body.reason()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    /** 驳回请求体。 */
    public record RejectRequest(String reason) {
    }

    private Mono<ServerResponse> listCategories(ServerRequest request) {
        // 同样按管辖收窄：这个列表供筛选下拉与「批量设分类」选项用，
        // 给分区版主看到管不了的分类，只会让他筛出空列表、或选中后被 403
        return currentUsername()
                .flatMap(moderationScope::visibleCategoryNames)
                .flatMap(scoped -> queryService.listCategories(false)
                        .filter(vo -> scoped.isEmpty() || scoped.get().contains(vo.getName()))
                        .collectList())
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    /**
     * 移动分类：前端只提交「挂到谁下面、排在谁之前」的意图，服务端重排同级序列，
     * 并把重排后的完整分类列表回给前端直接替换本地树（免二次拉取）。
     */
    private Mono<ServerResponse> moveCategory(ServerRequest request) {
        return request.bodyToMono(CategoryPositionRequest.class)
                .defaultIfEmpty(new CategoryPositionRequest(null, null))
                .flatMap(body -> categoryService.move(
                        request.pathVariable("name"), body.parentName(), body.beforeName()))
                .then(Mono.defer(() -> queryService.listCategories(false).collectList()))
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    /** 分类位置变更请求体（对齐官方 CategoryPositionRequest 的意图式语义）。 */
    public record CategoryPositionRequest(String parentName, String beforeName) {
    }

    private static Mono<ServerResponse> ok(Mono<?> mono) {
        return mono.flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

}
