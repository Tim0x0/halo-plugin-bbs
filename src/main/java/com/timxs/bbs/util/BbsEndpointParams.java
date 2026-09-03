package com.timxs.bbs.util;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.fn.builders.parameter.Builder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 端点公用的 springdoc 参数声明与查询参数读取。
 *
 * <p>Console / UC / Public 三个端点共用这套辅助，
 * 避免同一个参数契约在多处漂移。</p>
 *
 * @author Tim0x0
 */
public final class BbsEndpointParams {

    private BbsEndpointParams() {
    }

    /** 路径变量 {@code {name}}。 */
    public static Builder nameParam() {
        return parameterBuilder().name("name").in(ParameterIn.PATH)
                .required(true).implementation(String.class);
    }

    /** 路径变量 {@code {slug}}。 */
    public static Builder slugParam() {
        return parameterBuilder().name("slug").in(ParameterIn.PATH)
                .required(true).implementation(String.class);
    }

    /** 路径变量 {@code {commentName}}。 */
    public static Builder commentNameParam() {
        return parameterBuilder().name("commentName").in(ParameterIn.PATH)
                .required(true).implementation(String.class);
    }

    /** 路径变量 {@code {replyName}}。 */
    public static Builder replyNameParam() {
        return parameterBuilder().name("replyName").in(ParameterIn.PATH)
                .required(true).implementation(String.class);
    }

    /** 可选字符串查询参数。 */
    public static Builder queryParam(String name) {
        return queryParam(name, String.class);
    }

    /** 可选查询参数（指定类型）。 */
    public static Builder queryParam(String name, Class<?> type) {
        return parameterBuilder().name(name).in(ParameterIn.QUERY)
                .required(false).implementation(type);
    }

    /** 必填字符串查询参数。 */
    public static Builder requiredQueryParam(String name) {
        return parameterBuilder().name(name).in(ParameterIn.QUERY)
                .required(true).implementation(String.class);
    }

    /** 可选页码参数。 */
    public static Builder pageParam() {
        return queryParam("page", Integer.class);
    }

    /** 可选每页条数参数。 */
    public static Builder sizeParam() {
        return queryParam("size", Integer.class);
    }

    /** 必填的 snapshotName 查询参数（删除快照用）。 */
    public static Builder snapshotNameParam() {
        return requiredQueryParam("snapshotName");
    }

    /** 可选的 snapshotName 查询参数（取内容用，缺省取 head）。 */
    public static Builder optionalSnapshotNameParam() {
        return queryParam("snapshotName");
    }

    /** 读取必填查询参数；缺失或空白一律 400。 */
    public static String requiredQuery(ServerRequest request, String name) {
        return request.queryParam(name)
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "缺少参数 " + name));
    }

    /** 当前登录用户名；取不到即未登录，一律 401（需匿名语义的入口不适用）。 */
    public static Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .switchIfEmpty(Mono.error(() ->
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录")));
    }
}
