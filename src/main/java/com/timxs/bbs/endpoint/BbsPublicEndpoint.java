package com.timxs.bbs.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

import com.timxs.bbs.query.BbsQueryService;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
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
    private static final int MAX_PAGE_SIZE = 50;

    private final BbsQueryService queryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/posts", this::listPosts, builder -> builder
                        .operationId("ListBbsPostsPublic").tag(TAG)
                        .description("已发布内容分页（置顶按作用域浮顶；可按分类 name/slug、"
                                + "关键词、类型过滤；sort=active|latest|hot，默认最后活跃）")
                        .parameter(queryParam("page", Integer.class))
                        .parameter(queryParam("size", Integer.class))
                        .parameter(queryParam("categoryName", String.class))
                        .parameter(queryParam("categorySlug", String.class))
                        .parameter(queryParam("keyword", String.class))
                        .parameter(queryParam("sort", String.class))
                        .parameter(queryParam("type", String.class))
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(BbsPostVo.class))))
                .GET("/posts/{slug}", this::getPost, builder -> builder
                        .operationId("GetBbsPostPublic").tag(TAG)
                        .description("按 slug 取已发布帖子详情（含净化后的正文 HTML）")
                        .parameter(parameterBuilder().name("slug").in(ParameterIn.PATH)
                                .required(true).implementation(String.class))
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
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.bbs.timxs.com", "v1alpha1");
    }

    private Mono<ServerResponse> listPosts(ServerRequest request) {
        int size = Math.min(intParam(request, "size", 10), MAX_PAGE_SIZE);
        return queryService.listPublicPosts(
                        intParam(request, "page", 1),
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
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> listAnnouncements(ServerRequest request) {
        int limit = Math.min(intParam(request, "limit", 5), MAX_PAGE_SIZE);
        return queryService.listAnnouncements(limit)
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> listCategories(ServerRequest request) {
        return queryService.listCategories(true)
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private static int intParam(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }

    private static org.springdoc.core.fn.builders.parameter.Builder queryParam(String name,
            Class<?> type) {
        return parameterBuilder().name(name).in(ParameterIn.QUERY)
                .required(false).implementation(type);
    }
}
