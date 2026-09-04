package com.timxs.bbs.endpoint;

import static com.timxs.bbs.util.BbsEndpointParams.currentUsername;
import static com.timxs.bbs.util.BbsEndpointParams.nameParam;
import static com.timxs.bbs.util.BbsEndpointParams.optionalSnapshotNameParam;
import static com.timxs.bbs.util.BbsEndpointParams.pageParam;
import static com.timxs.bbs.util.BbsEndpointParams.queryParam;
import static com.timxs.bbs.util.BbsEndpointParams.requiredQuery;
import static com.timxs.bbs.util.BbsEndpointParams.sizeParam;
import static com.timxs.bbs.util.BbsEndpointParams.snapshotNameParam;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.query.BbsQueryService;
import com.timxs.bbs.service.BbsPostService;
import com.timxs.bbs.service.BbsSettings;
import com.timxs.bbs.service.ContentUpdateParam;
import com.timxs.bbs.service.PostRequest;
import com.timxs.bbs.service.RevertSnapshotParam;
import java.util.Map;
import com.timxs.bbs.util.BbsPageRequests;
import com.timxs.bbs.vo.BbsContentVo;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.BbsSnapshotDto;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;

/**
 * UC（用户中心）接口：登录用户管理自己的帖子。
 *
 * <p>安全规则：只能读写 owner 为自己的帖子（越权 403）；不能创建公告、不能置顶；
 * 新建与普通保存不改变流程状态，只有显式提交才按审核策略进入待审核或发布。
 * 路由顺序：字面量路由（{@code /mine}、{@code /slug-taken}）须在通配
 * {@code /{name}} 之前。</p>
 *
 * @author Tim0x0
 */
@Component
@RequiredArgsConstructor
public class BbsUcEndpoint implements CustomEndpoint {

    private static final String TAG = "BbsV1alpha1Uc";

    private final BbsPostService postService;
    private final BbsQueryService queryService;
    private final BbsSettings settings;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/config", this::getConfig, builder -> builder
                        .operationId("GetBbsUcConfig").tag(TAG)
                        .description("审核策略：UC 提交入口据此决定是否询问补充说明"
                                + "（免审直接提交，不构成打断）")
                        .response(responseBuilder().implementation(Map.class)))
                .GET("/bbsposts/mine", this::listMine, builder -> builder
                        .operationId("ListMyBbsPosts").tag(TAG)
                        .description("我的帖子列表（可选 phase / type / 标题关键词）")
                        .parameter(pageParam())
                        .parameter(sizeParam())
                        .parameter(queryParam("keyword"))
                        .parameter(queryParam("phase"))
                        .parameter(queryParam("categoryName"))
                        .parameter(queryParam("type"))
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(BbsPostVo.class))))
                // 字面量路由必须先于通配 /{name} 注册，否则 slug-taken 会被当成帖子名
                .GET("/bbsposts/slug-taken", this::isSlugTaken, builder -> builder
                        .operationId("CheckMyBbsPostSlugTaken").tag(TAG)
                        .description("别名占用预检：是否被发布中内容占用"
                                + "（已发布 / 待审核 / 已提交修改稿；草稿与回收站不占）；"
                                + "excludeName 排除自己")
                        .parameter(queryParam("slug"))
                        .parameter(queryParam("excludeName"))
                        .response(responseBuilder().implementation(Boolean.class)))
                .GET("/bbsposts/{name}", this::getMine, builder -> builder
                        .operationId("GetMyBbsPost").tag(TAG)
                        .description("取我的某篇帖子（含工作草稿正文；越权 403）")
                        .parameter(nameParam())
                        .response(responseBuilder().implementation(BbsPostVo.class)))
                .GET("/bbsposts/{name}/head-content", this::getHeadContent,
                        builder -> builder.operationId("GetMyBbsPostHeadContent").tag(TAG)
                                .description("取我的帖子工作版本正文")
                                .parameter(nameParam())
                                .response(responseBuilder()
                                        .implementation(BbsContentVo.class)))
                .GET("/bbsposts/{name}/release-content", this::getReleaseContent,
                        builder -> builder.operationId("GetMyBbsPostReleaseContent").tag(TAG)
                                .description("取我的帖子前台发布版本正文")
                                .parameter(nameParam())
                                .response(responseBuilder()
                                        .implementation(BbsContentVo.class)))
                .GET("/bbsposts/{name}/content", this::getContent,
                        builder -> builder.operationId("GetMyBbsPostContent").tag(TAG)
                                .description("还原我的指定快照正文（snapshotName 缺省取 head）")
                                .parameter(nameParam()).parameter(optionalSnapshotNameParam())
                                .response(responseBuilder()
                                        .implementation(BbsContentVo.class)))
                .GET("/bbsposts/{name}/snapshot", this::listSnapshots,
                        builder -> builder.operationId("ListMyBbsPostSnapshots").tag(TAG)
                                .description("列出我的帖子快照历史（不含正文）")
                                .parameter(nameParam())
                                .response(responseBuilder()
                                        .implementationArray(BbsSnapshotDto.class)))
                .PUT("/bbsposts/{name}/content", this::saveContent,
                        builder -> builder.operationId("UpdateMyBbsPostContent").tag(TAG)
                                .description("只保存正文；version 与服务端 head 不一致时分叉新快照")
                                .parameter(nameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(ContentUpdateParam.class))))
                                .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}/revert-content", this::revertContent,
                        builder -> builder.operationId("RevertMyBbsPostContent").tag(TAG)
                                .description("恢复历史版本：以旧内容新建快照成为 head；"
                                        + "未发布帖不发布，已发布帖按审核策略重发或送审")
                                .parameter(nameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(RevertSnapshotParam.class)))))
                .DELETE("/bbsposts/{name}/content", this::deleteContent,
                        builder -> builder.operationId("DeleteMyBbsPostContent").tag(TAG)
                                .description("删除我的快照；基线与发布版不可删，删 head 会先回退")
                                .parameter(nameParam()).parameter(snapshotNameParam()))
                .GET("/bbsposts/{name}/moderation-records", this::listModerationRecords,
                        builder -> builder.operationId("ListMyBbsModerationRecords").tag(TAG)
                                .description("列出我的帖子提交、通过、驳回等记录")
                                .parameter(nameParam()))
                .POST("/bbsposts", this::createPost, builder -> builder
                        .operationId("CreateBbsPostByUser").tag(TAG)
                        .description("创建我的服务端草稿（固定为 DRAFT；标题为空时兜底为未命名，分类可稍后补齐）")
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}", this::updateMine, builder -> builder
                        .operationId("UpdateMyBbsPost").tag(TAG)
                        .description("保存我的 head Snapshot（已发布帖不切换 release，不影响前台版本）")
                        .parameter(nameParam())
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}/submit", this::submitMine, builder -> builder
                        .operationId("SubmitMyBbsPost").tag(TAG)
                        .description("显式提交我的帖子或已发布修改（完整校验后按审核策略进入待审核或发布）")
                        .parameter(nameParam())
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}/withdraw", this::withdrawMine, builder -> builder
                        .operationId("WithdrawMyBbsPost").tag(TAG)
                        .description("撤回我的待审核提交（退回草稿；修改稿退回草稿态，前台发布版不受影响）")
                        .parameter(nameParam()))
                .PUT("/bbsposts/{name}/solve", this::solveMine, builder -> builder
                        .operationId("SolveMyBbsPost").tag(TAG)
                        .description("标记我的问答帖为已解决（越权 403，仅问答帖）")
                        .parameter(nameParam()))
                .PUT("/bbsposts/{name}/unsolve", this::unsolveMine, builder -> builder
                        .operationId("UnsolveMyBbsPost").tag(TAG)
                        .description("取消我的问答帖已解决标记（越权 403，仅问答帖）")
                        .parameter(nameParam()))
                .DELETE("/bbsposts/{name}", this::deleteMine, builder -> builder
                        .operationId("DeleteMyBbsPost").tag(TAG)
                        .description("删除我的帖子（越权 403；移入回收站，管理员可恢复）")
                        .parameter(nameParam()))
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("uc.api.bbs.timxs.com", "v1alpha1");
    }

    private Mono<ServerResponse> listMine(ServerRequest request) {
        return currentUsername()
                .flatMap(username -> queryService.listMine(
                        username,
                        BbsPageRequests.page(request, 1),
                        BbsPageRequests.size(request, 20, BbsPageRequests.MAX_UC),
                        request.queryParam("keyword").orElse(null),
                        request.queryParam("phase").orElse(null),
                        request.queryParam("categoryName").orElse(null),
                        request.queryParam("type").orElse(null)))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    /** 别名占用预检：只回布尔，不暴露占用者是谁（官方同款只判存在性）。 */
    /**
     * 审核策略出口：语义与提交链路一致——未发布内容提交看 postNeedsReview，
     * 已发布帖提交修改看 editNeedsReview（posting 免审时恒 false）。
     */
    private Mono<ServerResponse> getConfig(ServerRequest request) {
        return settings.content().flatMap(policy -> ServerResponse.ok().bodyValue(Map.of(
                "postNeedsReview", policy.required(),
                "editNeedsReview", policy.required() && policy.editNeedsReview())));
    }

    private Mono<ServerResponse> isSlugTaken(ServerRequest request) {
        return postService.isSlugTaken(
                        request.queryParam("slug").orElse(""),
                        request.queryParam("excludeName").orElse(null))
                .flatMap(taken -> ServerResponse.ok().bodyValue(taken));
    }

    private Mono<ServerResponse> getMine(ServerRequest request) {
        return currentUsername()
                .flatMap(username ->
                        postService.getOwned(request.pathVariable("name"), username))
                .flatMap(queryService::assembleEditingDetail)
                .flatMap(vo -> ServerResponse.ok().bodyValue(vo));
    }

    private Mono<ServerResponse> createPost(ServerRequest request) {
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.createOwnedDraft(tuple.getT1(), tuple.getT2()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> getHeadContent(ServerRequest request) {
        return currentUsername()
                .flatMap(owner -> postService.getHeadContentOwned(
                        request.pathVariable("name"), owner))
                .flatMap(content -> ServerResponse.ok().bodyValue(content));
    }

    private Mono<ServerResponse> getReleaseContent(ServerRequest request) {
        return currentUsername()
                .flatMap(owner -> postService.getReleaseContentOwned(
                        request.pathVariable("name"), owner))
                .flatMap(content -> ServerResponse.ok().bodyValue(content));
    }

    private Mono<ServerResponse> getContent(ServerRequest request) {
        return currentUsername()
                .flatMap(owner -> postService.getContentOwned(
                        request.pathVariable("name"),
                        request.queryParam("snapshotName").orElse(null), owner))
                .flatMap(content -> ServerResponse.ok().bodyValue(content));
    }

    private Mono<ServerResponse> listSnapshots(ServerRequest request) {
        return currentUsername()
                .flatMapMany(owner -> postService.listSnapshotsOwned(
                        request.pathVariable("name"), owner))
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> saveContent(ServerRequest request) {
        return Mono.zip(request.bodyToMono(ContentUpdateParam.class), currentUsername())
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "请提交正文")))
                .flatMap(tuple -> postService.saveContentOwned(
                        request.pathVariable("name"), tuple.getT1(), tuple.getT2()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> revertContent(ServerRequest request) {
        return Mono.zip(request.bodyToMono(RevertSnapshotParam.class), currentUsername())
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "请指定快照")))
                .flatMap(tuple -> postService.revertContentOwned(
                        request.pathVariable("name"), tuple.getT1().snapshotName(),
                        tuple.getT2()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> deleteContent(ServerRequest request) {
        return currentUsername()
                .flatMap(owner -> postService.deleteContentOwned(
                        request.pathVariable("name"),
                        requiredQuery(request, "snapshotName"), owner))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> listModerationRecords(ServerRequest request) {
        return currentUsername()
                .flatMapMany(owner -> postService.listModerationRecordsOwned(
                        request.pathVariable("name"), owner))
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> updateMine(ServerRequest request) {
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.saveOwned(
                        request.pathVariable("name"), tuple.getT1(), tuple.getT2()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> submitMine(ServerRequest request) {
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.submitOwned(
                        request.pathVariable("name"), tuple.getT1(), tuple.getT2()))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> withdrawMine(ServerRequest request) {
        return currentUsername()
                .flatMap(username -> postService.withdrawOwned(
                        request.pathVariable("name"), username))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> solveMine(ServerRequest request) {
        return currentUsername()
                .flatMap(username -> postService.setSolvedOwned(
                        request.pathVariable("name"), username, true))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> unsolveMine(ServerRequest request) {
        return currentUsername()
                .flatMap(username -> postService.setSolvedOwned(
                        request.pathVariable("name"), username, false))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> deleteMine(ServerRequest request) {
        // 用户侧删除同样进回收站——用户误删后管理员还能捞回来
        return currentUsername()
                .flatMap(username ->
                        postService.recycleOwned(request.pathVariable("name"), username))
                .then(ServerResponse.ok().build());
    }

}
