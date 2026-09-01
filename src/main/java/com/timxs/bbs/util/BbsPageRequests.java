package com.timxs.bbs.util;

import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * 列表分页参数：拒绝 0 / 非法值，并按端点封顶。
 *
 * @author Tim0x0
 */
public final class BbsPageRequests {

    public static final int MAX_PUBLIC = 50;
    public static final int MAX_CONSOLE = 100;
    public static final int MAX_UC = 50;

    private BbsPageRequests() {
    }

    /** 页码：正整数，缺省 / 非法回退 defaultValue。 */
    public static int page(ServerRequest request, int defaultValue) {
        return positiveInt(request, "page", defaultValue);
    }

    /** 每页条数：正整数，缺省 / 非法回退 defaultValue，再封顶 max。 */
    public static int size(ServerRequest request, int defaultValue, int max) {
        return Math.min(max, positiveInt(request, "size", defaultValue));
    }

    /** 通用正整数查询参数（limit 等）。 */
    public static int positiveInt(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
                .filter(s -> s.matches("[1-9]\\d{0,8}"))
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }
}
