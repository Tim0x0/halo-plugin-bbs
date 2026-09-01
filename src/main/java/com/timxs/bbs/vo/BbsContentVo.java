package com.timxs.bbs.vo;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import run.halo.app.content.ContentWrapper;

/**
 * 正文内容响应，对齐 Halo 官方 {@code Content}（raw / content / rawType）。
 *
 * <p>{@code /content}、{@code /head-content}、{@code /release-content} 三个端点共用。
 * 官方原样返回快照内容，BBS 这里保留服务端净化（见 {@code BbsPostContentService}）：
 * 正文由普通用户产出，纵深防御不跟随官方放宽。</p>
 */
@Schema(name = "BbsContent")
public record BbsContentVo(
        @Schema(requiredMode = REQUIRED, description = "编辑器原文") String raw,
        @Schema(requiredMode = REQUIRED, description = "渲染后正文") String content,
        @Schema(requiredMode = REQUIRED, description = "原文类型，固定 html") String rawType,
        @Schema(description = "内容所属快照 metadata.name") String snapshotName,
        @Schema(description = "内容所属快照的乐观锁版本，保存时回传做冲突检测") Long version) {

    public static BbsContentVo from(ContentWrapper wrapper, Long version) {
        return new BbsContentVo(wrapper.getRaw(), wrapper.getContent(), wrapper.getRawType(),
                wrapper.getSnapshotName(), version);
    }
}
