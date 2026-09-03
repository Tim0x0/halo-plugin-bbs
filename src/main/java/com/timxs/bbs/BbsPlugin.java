package com.timxs.bbs;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsModerationRecord;
import com.timxs.bbs.extension.BbsPost;
import java.time.Instant;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * BBS 社区插件主类：负责自定义模型的注册与索引声明。
 *
 * @author Tim0x0
 */
@Slf4j
@Component
public class BbsPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public BbsPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(BbsCategory.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<BbsCategory, String>single("spec.slug", String.class)
                    .unique(true)
                    .nullable(false)
                    .indexFunc(c -> c.getSpec().getSlug()));
            indexSpecs.add(IndexSpecs.<BbsCategory, String>single("spec.parentName", String.class)
                    .indexFunc(c -> c.getSpec().getParentName()));
            indexSpecs.add(IndexSpecs.<BbsCategory, Integer>single("spec.priority", Integer.class)
                    .indexFunc(c -> c.getSpec().getPriority()));
            // null 视为启用（字段默认 true）；查询 equal(true) 才能命中未显式写 enabled 的分类
            indexSpecs.add(IndexSpecs.<BbsCategory, Boolean>single("spec.enabled", Boolean.class)
                    .indexFunc(c -> !Boolean.FALSE.equals(c.getSpec().getEnabled())));
        });

        schemeManager.register(BbsPost.class, indexSpecs -> {
            // 不设 unique：草稿/驳回稿与回收站不占公开别名；进入待审/发布前由服务层校验
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.slug", String.class)
                    .nullable(false)
                    .indexFunc(p -> p.getSpec().getSlug()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.title", String.class)
                    .indexFunc(p -> p.getSpec().getTitle()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.type", String.class)
                    .indexFunc(p -> enumName(p.getSpec().getType())));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.categoryName", String.class)
                    .indexFunc(p -> p.getSpec().getCategoryName()));
            indexSpecs.add(IndexSpecs.<BbsPost, Boolean>single("spec.pinned", Boolean.class)
                    .indexFunc(p -> Boolean.TRUE.equals(p.getSpec().getPinned())));
            indexSpecs.add(IndexSpecs.<BbsPost, Integer>single("spec.pinPriority", Integer.class)
                    .indexFunc(p -> p.getSpec().getPinPriority() == null
                            ? 0 : p.getSpec().getPinPriority()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.phase", String.class)
                    .indexFunc(p -> enumName(p.getSpec().getPhase())));
            // 已发布帖子的工作草稿等价于 Halo head snapshot。公开查询绝不读这些索引；
            // Console / UC 只用它们检索修改稿与待审核版本。
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.draft.title", String.class)
                    .indexFunc(p -> p.getSpec().getDraft() == null
                            ? null : p.getSpec().getDraft().getTitle()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.draft.slug", String.class)
                    .indexFunc(p -> p.getSpec().getDraft() == null
                            ? null : p.getSpec().getDraft().getSlug()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.draft.type", String.class)
                    .indexFunc(p -> p.getSpec().getDraft() == null
                            ? null : enumName(p.getSpec().getDraft().getType())));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single(
                            "spec.draft.categoryName", String.class)
                    .indexFunc(p -> p.getSpec().getDraft() == null
                            ? null : p.getSpec().getDraft().getCategoryName()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.draft.phase", String.class)
                    .indexFunc(p -> p.getSpec().getDraft() == null
                            ? null : enumName(p.getSpec().getDraft().getPhase())));
            indexSpecs.add(IndexSpecs.<BbsPost, Instant>single("spec.publishTime", Instant.class)
                    .indexFunc(p -> p.getSpec().getPublishTime()));
            // 最后活跃时间：字段缺失时回退发布时间，「最后活跃」排序始终可用
            indexSpecs.add(IndexSpecs.<BbsPost, Instant>single(
                            "spec.lastActivityTime", Instant.class)
                    .indexFunc(p -> p.getSpec().getLastActivityTime() != null
                            ? p.getSpec().getLastActivityTime()
                            : p.getSpec().getPublishTime()));
            // 最后编辑时间：Console「最后编辑」排序依据；从未编辑过的帖子该值为 null
            indexSpecs.add(IndexSpecs.<BbsPost, Instant>single("spec.lastEditTime", Instant.class)
                    .indexFunc(p -> p.getSpec().getLastEditTime()));
            indexSpecs.add(IndexSpecs.<BbsPost, Boolean>single("spec.deleted", Boolean.class)
                    .indexFunc(p -> Boolean.TRUE.equals(p.getSpec().getDeleted())));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.owner", String.class)
                    .indexFunc(p -> p.getSpec().getOwner()));
            // 评论数：由调和器维护在 status 里，建索引后「按评论数排序」与「热门」
            // 才能走数据库排序，而不必扫一批到内存里排
            indexSpecs.add(IndexSpecs.<BbsPost, Integer>single(
                            "status.commentsCount", Integer.class)
                    .indexFunc(p -> {
                        var status = p.getStatus();
                        return status == null || status.getCommentsCount() == null
                                ? 0 : status.getCommentsCount();
                    }));
        });

        schemeManager.register(BbsModerationRecord.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<BbsModerationRecord, String>single(
                            "spec.postName", String.class)
                    .nullable(false)
                    .indexFunc(record -> record.getSpec().getPostName()));
            indexSpecs.add(IndexSpecs.<BbsModerationRecord, String>single(
                            "spec.action", String.class)
                    .indexFunc(record -> enumName(record.getSpec().getAction())));
            indexSpecs.add(IndexSpecs.<BbsModerationRecord, Instant>single(
                            "spec.createdAt", Instant.class)
                    .nullable(false)
                    .indexFunc(record -> record.getSpec().getCreatedAt()));
        });

        log.info("BBS 社区插件启动成功");
    }

    @Override
    public void stop() {
        Stream.of(BbsModerationRecord.class, BbsPost.class, BbsCategory.class)
                .forEach(type -> schemeManager.unregister(Scheme.buildFromType(type)));
        log.info("BBS 社区插件已停止");
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
