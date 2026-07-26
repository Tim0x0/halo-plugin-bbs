package com.timxs.bbs.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.query.BbsQueryService;
import com.timxs.bbs.service.BbsPostService;
import com.timxs.bbs.service.PostRequest;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
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
    private final BbsQueryService queryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/bbsposts", this::listPosts, builder -> builder
                        .operationId("ListBbsPostsConsole").tag(TAG)
                        .description("帖子管理列表（keyword/categoryName/type/phase 筛选；"
                                + "sort 白名单：creationTimestamp|publishTime,asc|desc）")
                        .parameter(pageParam()).parameter(sizeParam())
                        .parameter(queryParam("keyword")).parameter(queryParam("categoryName"))
                        .parameter(queryParam("type")).parameter(queryParam("phase"))
                        .parameter(queryParam("sort"))
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(BbsPostVo.class))))
                .POST("/bbsposts", this::createPost, builder -> builder
                        .operationId("CreateBbsPostConsole").tag(TAG)
                        .description("后台创建帖子/公告（publish=true 直接发布）")
                        .parameter(parameterBuilder().name("publish").in(ParameterIn.QUERY)
                                .required(false).implementation(Boolean.class))
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                .GET("/bbsposts/{name}", this::getPost, builder -> builder
                        .operationId("GetBbsPostConsole").tag(TAG)
                        .description("取单篇帖子详情（含正文，编辑用）")
                        .parameter(nameParam())
                        .response(responseBuilder().implementation(BbsPostVo.class)))
                .PUT("/bbsposts/{name}", this::updatePost, builder -> builder
                        .operationId("UpdateBbsPostConsole").tag(TAG)
                        .description("后台更新帖子（含管理字段）")
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
                                .description("撤销发布（回到草稿）").parameter(nameParam()))
                .PUT("/bbsposts/{name}/approve",
                        req -> ok(postService.publish(req.pathVariable("name"))),
                        builder -> builder.operationId("ApproveBbsPost").tag(TAG)
                                .description("审核通过并发布").parameter(nameParam()))
                .PUT("/bbsposts/{name}/reject", this::rejectPost,
                        builder -> builder.operationId("RejectBbsPost").tag(TAG)
                                .description("审核驳回（可附驳回原因，展示给作者）")
                                .parameter(nameParam())
                                .requestBody(requestBodyBuilder().content(contentBuilder()
                                        .schema(schemaBuilder()
                                                .implementation(RejectRequest.class)))))
                .PUT("/bbsposts/{name}/pin",
                        req -> ok(postService.pin(req.pathVariable("name"))),
                        builder -> builder.operationId("PinBbsPost").tag(TAG)
                                .description("置顶").parameter(nameParam()))
                .PUT("/bbsposts/{name}/unpin",
                        req -> ok(postService.unpin(req.pathVariable("name"))),
                        builder -> builder.operationId("UnpinBbsPost").tag(TAG)
                                .description("取消置顶").parameter(nameParam()))
                .DELETE("/bbsposts/{name}",
                        req -> postService.delete(req.pathVariable("name"), null)
                                .then(ServerResponse.ok().build()),
                        builder -> builder.operationId("DeleteBbsPostConsole").tag(TAG)
                                .description("删除帖子").parameter(nameParam()))
                .GET("/bbscategories", this::listCategories, builder -> builder
                        .operationId("ListBbsCategoriesConsole").tag(TAG)
                        .description("分类管理列表（含已发布帖子数，priority 升序）")
                        .response(responseBuilder().implementationArray(CategoryVo.class)))
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.bbs.timxs.com", "v1alpha1");
    }

    private Mono<ServerResponse> listPosts(ServerRequest request) {
        return queryService.listConsole(
                        intParam(request, "page", 1),
                        intParam(request, "size", 20),
                        request.queryParam("keyword").orElse(null),
                        request.queryParam("categoryName").orElse(null),
                        request.queryParam("type").orElse(null),
                        request.queryParam("phase").orElse(null),
                        request.queryParam("sort").orElse(null))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> createPost(ServerRequest request) {
        boolean publish = request.queryParam("publish")
                .map(Boolean::parseBoolean).orElse(false);
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.create(tuple.getT1(), tuple.getT2(), true, publish))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> getPost(ServerRequest request) {
        return postService.getRequired(request.pathVariable("name"))
                .flatMap(queryService::assembleDetail)
                .flatMap(vo -> ServerResponse.ok().bodyValue(vo));
    }

    private Mono<ServerResponse> updatePost(ServerRequest request) {
        return request.bodyToMono(PostRequest.class)
                .flatMap(body -> postService.update(
                        request.pathVariable("name"), body, null, true))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
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
        return queryService.listCategories(false)
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private static Mono<ServerResponse> ok(Mono<?> mono) {
        return mono.flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    private static Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .switchIfEmpty(Mono.error(() ->
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录")));
    }

    private static int intParam(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }

    private static org.springdoc.core.fn.builders.parameter.Builder pageParam() {
        return parameterBuilder().name("page").in(ParameterIn.QUERY)
                .required(false).implementation(Integer.class);
    }

    private static org.springdoc.core.fn.builders.parameter.Builder sizeParam() {
        return parameterBuilder().name("size").in(ParameterIn.QUERY)
                .required(false).implementation(Integer.class);
    }

    private static org.springdoc.core.fn.builders.parameter.Builder nameParam() {
        return parameterBuilder().name("name").in(ParameterIn.PATH)
                .required(true).implementation(String.class);
    }

    private static org.springdoc.core.fn.builders.parameter.Builder queryParam(String name) {
        return parameterBuilder().name(name).in(ParameterIn.QUERY)
                .required(false).implementation(String.class);
    }
}
