package com.timxs.bbs.service;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 恢复历史版本的请求体，对齐 Halo 官方 {@code RevertSnapshotParam}。
 *
 * @param snapshotName 要恢复的快照 metadata.name
 */
@Schema(name = "BbsRevertSnapshotParam")
public record RevertSnapshotParam(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "要恢复的快照名")
        String snapshotName) {
}
