package com.timxs.bbs.integration;

import com.timxs.bbs.event.BbsPostChangedEvent;
import com.timxs.bbs.finder.BbsFinder;
import com.timxs.bbs.service.BbsSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.feed.CacheClearRule;
import run.halo.feed.RssCacheClearRequested;

/**
 * plugin-feed 集成装配：仅当 plugin-feed 在场（{@code run.halo.feed.RssRouteItem} 可加载）
 * 时整个配置类才生效——注册 BBS 的 RSS 源贡献 bean，并监听 BBS 帖子变更以清理
 * plugin-feed 的 RSS 缓存，避免订阅端最长 1 小时的更新延迟。
 *
 * <p>采用<b>类级</b> {@code @ConditionalOnClass}（与 {@link InteractionPlusIntegration} 的方法级不同，
 * 因本类需承载 {@link EventListener}）：plugin-feed 缺席时本类整体不被加载，其引用的
 * plugin-feed 类型（{@link CacheClearRule} / {@link RssCacheClearRequested}）永不被解析，
 * 避免 {@link NoClassDefFoundError}。条件评估走 ASM 读注解、不触发类加载，故 {@code @Bean}
 * 方法返回具体类型 {@link BbsRssRouteItem}（implements RssRouteItem）亦安全。
 * 对齐官方 plugin-moments 的 {@code RssAutoConfiguration} 做法。</p>
 *
 * @author Tim0x0
 */
@Configuration
@ConditionalOnClass(name = "run.halo.feed.RssRouteItem")
@RequiredArgsConstructor
public class BbsFeedIntegration {

    private final BbsFinder bbsFinder;
    private final BbsSettings settings;
    private final ExternalUrlSupplier externalUrlSupplier;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * BBS 的全站 RSS 订阅源（最新帖子与公告），供 plugin-feed 经 ExtensionGetter 按
     * RssRouteItem 接口发现，在 {@code /feed/bbs/posts.xml} 输出。
     */
    @Bean
    BbsRssRouteItem bbsRssRouteItem() {
        return new BbsRssRouteItem(bbsFinder, settings, externalUrlSupplier);
    }

    /** 一级分类 RSS 订阅源（含子分类内容），{@code /feed/bbs/categories/{slug}.xml}。 */
    @Bean
    BbsCategoryRssRouteItem bbsCategoryRssRouteItem() {
        return new BbsCategoryRssRouteItem(bbsFinder, settings, externalUrlSupplier);
    }

    /**
     * BBS 帖子任何变更（发布 / 编辑 / 删除 / 审核 / 置顶）后，按前缀清理 BBS 全部
     * RSS 缓存（全站 + 各分类 feed）。异步执行，不阻塞事件发布方。
     */
    @Async
    @EventListener(BbsPostChangedEvent.class)
    public void onBbsPostChanged() {
        var rule = CacheClearRule.forPrefix("/feed/bbs/");
        eventPublisher.publishEvent(RssCacheClearRequested.forRule(this, rule));
    }
}
