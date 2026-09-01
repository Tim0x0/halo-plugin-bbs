package com.timxs.bbs.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 帖子审核审计记录。
 *
 * <p>记录采用只追加模型：当前帖子上的 rejectReason 只负责当前状态展示，本资源保存每次
 * 提交、通过、驳回、取消发布和快照恢复的历史，并绑定当时的 Snapshot，避免审核结论
 * 与后来继续自动保存的 head 混淆。</p>
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "bbs.timxs.com",
        version = "v1alpha1",
        kind = "BbsModerationRecord",
        plural = "bbsmoderationrecords",
        singular = "bbsmoderationrecord")
public class BbsModerationRecord extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private Spec spec = new Spec();

    @Data
    @Schema(name = "BbsModerationRecordSpec")
    public static class Spec {

        @Schema(requiredMode = REQUIRED, description = "BbsPost metadata.name")
        private String postName;

        @Schema(requiredMode = REQUIRED, description = "审核或版本事件")
        private Action action;

        @Schema(requiredMode = REQUIRED, description = "操作者 User metadata.name")
        private String actor;

        @Schema(description = "事件对应的 Snapshot metadata.name")
        private String snapshotName;

        @Schema(description = "操作前流程状态")
        private String fromPhase;

        @Schema(description = "操作后流程状态")
        private String toPhase;

        @Schema(description = "驳回原因或补充说明", maxLength = 500)
        private String reason;

        @Schema(requiredMode = REQUIRED, description = "事件发生时间")
        private Instant createdAt;
    }

    public enum Action {
        SUBMITTED,
        PUBLISHED,
        APPROVED,
        REJECTED,
        SUBMISSION_WITHDRAWN,
        UNPUBLISHED
    }
}
