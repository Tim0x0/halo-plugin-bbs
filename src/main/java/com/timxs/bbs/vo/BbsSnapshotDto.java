package com.timxs.bbs.vo;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.experimental.Accessors;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.MetadataOperator;

/**
 * 快照列表项，逐字段对齐 Halo 官方 {@code ListedSnapshotDto}。
 *
 * <p>只透传快照自身的 metadata 与创建者/修改时间——业务元数据（标题、分类等）活在帖子
 * 本体上，不入版本链。前端的「基础 / 工作中 / 已发布」徽标由帖子的三指针
 * （{@code baseSnapshot} / {@code headSnapshot} / {@code releaseSnapshot}）与
 * {@code metadata.name} 比对得出，与官方 Console 的做法一致。</p>
 */
@Data
@Accessors(chain = true)
@Schema(name = "BbsSnapshot")
public class BbsSnapshotDto {

    @Schema(requiredMode = REQUIRED, description = "快照 metadata 原样透传（含 name / version "
            + "/ creationTimestamp）")
    private MetadataOperator metadata;

    @Schema(requiredMode = REQUIRED)
    private Spec spec;

    @Data
    @Accessors(chain = true)
    @Schema(name = "BbsSnapshotSpec")
    public static class Spec {

        @Schema(description = "快照创建者")
        private String owner;

        @Schema(description = "最后修改时间")
        private Instant modifyTime;
    }

    public static BbsSnapshotDto from(Snapshot snapshot) {
        return new BbsSnapshotDto()
                .setMetadata(snapshot.getMetadata())
                .setSpec(new Spec()
                        .setOwner(snapshot.getSpec().getOwner())
                        .setModifyTime(snapshot.getSpec().getLastModifyTime()));
    }
}
