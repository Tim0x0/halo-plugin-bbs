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
 * UC（用户中心）接口：登录用户管理自己的帖子。
 *
 * <p>安全规则：只能读写 owner 为自己的帖子（越权 403）；类型固定为普通帖子，
 * 不能置顶；发布即生效。路由顺序：{@code /mine} 须在通配 {@code /{name}} 之前。</p>
 *
 * @author Tim0x0
 */
@Component
@RequiredArgsConstructor
public class BbsUcEndpoint implements CustomEndpoint {

    private static final String TAG = "BbsV1alpha1Uc";

    private final BbsPostService postService;
    private final BbsQueryService queryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/bbsposts/mine", this::listMine, builder -> builder
                        .operationId("ListMyBbsPosts").tag(TAG)
                        .description("我的帖子列表（可选 phase / type / 标题关键词）")
                        .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY)
                                .required(false).implementation(Integer.class))
                        .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY)
                                .required(false).implementation(Integer.class))
                        .parameter(parameterBuilder().name("keyword").in(ParameterIn.QUERY)
                                .required(false).implementation(String.class))
                        .parameter(parameterBuilder().name("phase").in(ParameterIn.QUERY)
                                .required(false).implementation(String.class))
                        .parameter(parameterBuilder().name("categoryName").in(ParameterIn.QUERY)
                                .required(false).implementation(String.class))
                        .parameter(parameterBuilder().name("type").in(ParameterIn.QUERY)
                                .required(false).implementation(String.class))
                        .response(responseBuilder().implementation(
                                ListResult.generateGenericClass(BbsPostVo.class))))
                .GET("/bbsposts/{name}", this::getMine, builder -> builder
                        .operationId("GetMyBbsPost").tag(TAG)
                        .description("取我的某篇帖子（含正文，越权 403）")
                        .parameter(nameParam())
                        .response(responseBuilder().implementation(BbsPostVo.class)))
                .POST("/bbsposts", this::createPost, builder -> builder
                        .operationId("CreateBbsPostByUser").tag(TAG)
                        .description("用户发帖（固定为普通帖子，发布即生效）")
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
                .PUT("/bbsposts/{name}", this::updateMine, builder -> builder
                        .operationId("UpdateMyBbsPost").tag(TAG)
                        .description("更新我的帖子（越权 403；锁定帖不可编辑）")
                        .parameter(nameParam())
                        .requestBody(requestBodyBuilder().content(contentBuilder()
                                .schema(schemaBuilder().implementation(PostRequest.class))))
                        .response(responseBuilder().implementation(BbsPost.class)))
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
                        .description("删除我的帖子（越权 403）")
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
                        intParam(request, "page", 1),
                        intParam(request, "size", 20),
                        request.queryParam("keyword").orElse(null),
                        request.queryParam("phase").orElse(null),
                        request.queryParam("categoryName").orElse(null),
                        request.queryParam("type").orElse(null)))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> getMine(ServerRequest request) {
        return currentUsername()
                .flatMap(username ->
                        postService.getOwned(request.pathVariable("name"), username))
                .flatMap(queryService::assembleDetail)
                .flatMap(vo -> ServerResponse.ok().bodyValue(vo));
    }

    private Mono<ServerResponse> createPost(ServerRequest request) {
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.create(tuple.getT1(), tuple.getT2(), false, true))
                .flatMap(post -> ServerResponse.ok().bodyValue(post));
    }

    private Mono<ServerResponse> updateMine(ServerRequest request) {
        return Mono.zip(request.bodyToMono(PostRequest.class), currentUsername())
                .flatMap(tuple -> postService.update(
                        request.pathVariable("name"), tuple.getT1(), tuple.getT2(), false))
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
        return currentUsername()
                .flatMap(username ->
                        postService.delete(request.pathVariable("name"), username))
                .then(ServerResponse.ok().build());
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

    private static org.springdoc.core.fn.builders.parameter.Builder nameParam() {
        return parameterBuilder().name("name").in(ParameterIn.PATH)
                .required(true).implementation(String.class);
    }
}
