package com.timxs.bbs.endpoint;

import static com.timxs.bbs.util.BbsEndpointParams.nameParam;
import static com.timxs.bbs.util.BbsEndpointParams.pageParam;
import static com.timxs.bbs.util.BbsEndpointParams.queryParam;
import static com.timxs.bbs.util.BbsEndpointParams.sizeParam;
import static com.timxs.bbs.util.BbsEndpointParams.slugParam;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

import com.timxs.bbs.query.BbsQueryService;
import com.timxs.bbs.util.BbsPageRequests;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import com.timxs.bbs.vo.RoCommentVo;
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
 * 公开（主题端）接口：匿名可读的已发布内容。供主题 JS / 第三方前端消费；
 * 模板侧等价能力见 {@code BbsFinder}（模板变量 {@code ${bbs}}）。
 *
 * <p>DTO 自包含：分类与作者的展示属性内联，消费方无需二次请求；
 * 消费方应忽略未知字段（演进只增不改）。</p>
 *
 * @author Tim0x0
 */
@Component
@RequiredArgsConstructor
public class BbsPublicEndpoint implements CustomEndpoint {

    private static final String TAG = "BbsV1alpha1Public";

    /**
     * 只读评论列表里每条评论预取的楼中楼条数。
     *
     * <p>刻意不做成设置项：只读评论区仅锁定帖可见，属低频场景，
     * 多一个配置项不值当。超出的回复由前端点「展开」再走 replies 分页。</p>
     */
    private static final int RO_REPLY_PREVIEW = 3;

    private final BbsQueryService queryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/posts", this::listPosts, builder -> builder
                        .operationId("ListBbsPostsPublic").tag(TAG)
                        .description("已发布内容分页（置顶按作用域浮顶；可按分类 name/slug、"
                                + "关键词、类型过滤；sort=active|latest|hot，默认最后活跃）")
                        .parameter(pageParam())
                        .parameter(sizeParam())
                        .parameter(queryParam("categoryName"))
                        .parameter(queryParam("categorySlug"))
                        .parameter(queryParam("keyword"))
                        .parameter(queryParam("sort"))
                        .parameter(queryParam("type"))
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(BbsPostVo.class))))
                .GET("/posts/{slug}", this::getPost, builder -> builder
                        .operationId("GetBbsPostPublic").tag(TAG)
                        .description("按 slug 取已发布帖子详情（含净化后的正文 HTML）")
                        .parameter(slugParam())
                        .response(responseBuilder().implementation(BbsPostVo.class)))
                .GET("/announcements", this::listAnnouncements, builder -> builder
                        .operationId("ListBbsAnnouncementsPublic").tag(TAG)
                        .description("已发布公告（置顶权重与发布时间倒序）")
                        .parameter(queryParam("limit", Integer.class))
                        .response(responseBuilder().implementationArray(BbsPostVo.class)))
                .GET("/categories", this::listCategories, builder -> builder
                        .operationId("ListBbsCategoriesPublic").tag(TAG)
                        .description("启用中的分类（priority 升序，含已发布帖子数）")
                        .response(responseBuilder().implementationArray(CategoryVo.class)))
                .GET("/posts/{name}/comments", this::listRoComments, builder -> builder
                        .operationId("ListBbsRoCommentsPublic").tag(TAG)
                        .description("锁定帖的只读评论（owner.name 仅 User kind 返回，供装扮；"
                                + "Email kind 不返回 name 防 email 泄露）")
                        .parameter(nameParam())
                        .parameter(pageParam())
                        .parameter(sizeParam())
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(RoCommentVo.class))))
                .GET("/comments/{name}/replies", this::listRoReplies, builder -> builder
                        .operationId("ListBbsRoRepliesPublic").tag(TAG)
                        .description("某评论的只读楼中楼回复（同 listRoComments 装扮 + 批量）")
                        .parameter(nameParam())
                        .parameter(pageParam())
                        .parameter(sizeParam())
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(RoCommentVo.class))))
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.bbs.timxs.com", "v1alpha1");
    }

    private Mono<ServerResponse> listPosts(ServerRequest request) {
        int size = BbsPageRequests.size(request, 10, BbsPageRequests.MAX_PUBLIC);
        return queryService.listPublicPostsOrNotFound(
                        BbsPageRequests.page(request, 1),
                        size,
                        request.queryParam("categoryName").orElse(null),
                        request.queryParam("categorySlug").orElse(null),
                        request.queryParam("keyword").orElse(null),
                        request.queryParam("sort").orElse(null),
                        request.queryParam("type").orElse(null))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> getPost(ServerRequest request) {
        return queryService.getPublishedBySlug(request.pathVariable("slug"))
                .flatMap(vo -> ServerResponse.ok().bodyValue(vo))
                // API 语义的 404：带原因，消费方能读到 ProblemDetail
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "帖子不存在")));
    }

    private Mono<ServerResponse> listAnnouncements(ServerRequest request) {
        int limit = Math.min(BbsPageRequests.positiveInt(request, "limit", 5),
                BbsPageRequests.MAX_PUBLIC);
        return queryService.listAnnouncements(limit)
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> listCategories(ServerRequest request) {
        return queryService.listCategories(true)
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> listRoComments(ServerRequest request) {
        int size = BbsPageRequests.size(request, 20, BbsPageRequests.MAX_PUBLIC);
        return queryService.listRoComments(
                        request.pathVariable("name"),
                        BbsPageRequests.page(request, 1),
                        size,
                        RO_REPLY_PREVIEW)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> listRoReplies(ServerRequest request) {
        int size = BbsPageRequests.size(request, 50, BbsPageRequests.MAX_PUBLIC);
        return queryService.listRoReplies(
                        request.pathVariable("name"),
                        BbsPageRequests.page(request, 1),
                        size)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }
}
