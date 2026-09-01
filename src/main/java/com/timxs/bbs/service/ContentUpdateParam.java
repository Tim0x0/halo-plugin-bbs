package com.timxs.bbs.service;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 只保存正文的请求体，对齐 Halo 官方 {@code ContentUpdateParam}。
 *
 * <p>{@code version} 是客户端载入时 head 快照的 {@code metadata.version}：与服务端当前
 * head 不一致说明另一处已经改过，服务端会分叉出新快照而不是覆盖对方的版本。</p>
 *
 * @param version 客户端载入时的 head 快照乐观锁版本；为空则不做并发检测
 * @param raw 编辑器原文
 * @param content 渲染后正文（BBS 的编辑器产出 HTML，两者同源）
 * @param rawType 原文类型，缺省 html
 */
@Schema(name = "BbsContentUpdateParam")
public record ContentUpdateParam(
        @Schema(description = "客户端载入时的 head 快照 metadata.version（并发编辑检测）")
        Long version,
        @Schema(description = "编辑器原文") String raw,
        @Schema(description = "渲染后正文（服务端会做白名单净化）") String content,
        @Schema(description = "原文类型，缺省 html") String rawType) {

    /** BBS 编辑器产出 HTML：raw 与 content 同源，取到哪个用哪个。 */
    public String resolvedContent() {
        return content == null ? raw : content;
    }
}
