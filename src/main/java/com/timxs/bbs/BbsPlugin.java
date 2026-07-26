package com.timxs.bbs;

import com.timxs.bbs.extension.BbsCategory;
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
            indexSpecs.add(IndexSpecs.<BbsCategory, Integer>single("spec.priority", Integer.class)
                    .indexFunc(c -> c.getSpec().getPriority()));
            indexSpecs.add(IndexSpecs.<BbsCategory, Boolean>single("spec.enabled", Boolean.class)
                    .indexFunc(c -> c.getSpec().getEnabled()));
        });

        schemeManager.register(BbsPost.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.slug", String.class)
                    .unique(true)
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
            indexSpecs.add(IndexSpecs.<BbsPost, Instant>single("spec.publishTime", Instant.class)
                    .indexFunc(p -> p.getSpec().getPublishTime()));
            indexSpecs.add(IndexSpecs.<BbsPost, String>single("spec.owner", String.class)
                    .indexFunc(p -> p.getSpec().getOwner()));
        });

        log.info("BBS 社区插件启动成功");
    }

    @Override
    public void stop() {
        Stream.of(BbsPost.class, BbsCategory.class)
                .forEach(type -> schemeManager.unregister(Scheme.buildFromType(type)));
        log.info("BBS 社区插件已停止");
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
