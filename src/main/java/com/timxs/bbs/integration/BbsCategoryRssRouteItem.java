package com.timxs.bbs.integration;

import com.timxs.bbs.finder.BbsFinder;
import com.timxs.bbs.router.BbsRouter.AppearanceSetting;
import com.timxs.bbs.router.BbsRouter.AppearanceSetting.Brand;
import com.timxs.bbs.router.BbsRouter.BrowsingSetting;
import com.timxs.bbs.router.BbsRouter.BrowsingSetting.Rss;
import com.timxs.bbs.vo.BbsPostVo;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.feed.RSS2;
import run.halo.feed.RssRouteItem;

/**
 * 一级分类 RSS 源：{@code /feed/bbs/categories/{slug}.xml}，内容为该一级分类树内
 * （本级 + 子分类）的最新已发布帖子——与前台分类页合并口径一致。
 *
 * <p>仅提供一级分类粒度：子分类不给独立 feed；未选分类的帖子不属于任何分类树，
 * 只进全站 feed（{@link BbsRssRouteItem}）。
 * 传子分类 slug 时按其自身内容输出（不推荐订阅，但不报错）。</p>
 *
 * <p>路径变量语法与官方 plugin-feed 内置 provider（{@code /categories/{slug}.xml}）一致；
 * 注册方式同 {@link BbsRssRouteItem}——由 {@link BbsFeedIntegration} 在 plugin-feed
 * 在场时以 {@code @Bean} 注册，不标 {@code @Component}。</p>
 *
 * @author Tim0x0
 */
@RequiredArgsConstructor
public class BbsCategoryRssRouteItem implements RssRouteItem {

    private static final int DEFAULT_RSS_SIZE = 20;
    private static final int MAX_RSS_SIZE = 100;

    private final BbsFinder bbsFinder;
    private final ReactiveSettingFetcher settingFetcher;
    private final ExternalUrlSupplier externalUrlSupplier;

    @Override
    public Mono<String> pathPattern() {
        return Mono.just("/categories/{slug}.xml");
    }

    @Override
    public String displayName() {
        return "BBS 社区分类";
    }

    @Override
    public String description() {
        return "订阅 BBS 社区某一级分类（含其子分类）最新发布的帖子";
    }

    @Override
    public String namespace() {
        return "bbs";
    }

    @Override
    public String example() {
        return "/feed/bbs/categories/{slug}.xml";
    }

    @Override
    public Mono<RSS2> handler(ServerRequest request) {
        var slug = request.pathVariable("slug");
        var externalUrl = externalUrlSupplier
                .getURL(request.exchange().getRequest()).toString();
        return Mono.zip(brandSetting(), rssSetting())
                .flatMap(t -> {
                    var brand = t.getT1();
                    var rss = t.getT2();
                    int size = (rss.rssSize() == null || rss.rssSize() < 1)
                            ? DEFAULT_RSS_SIZE : Math.min(MAX_RSS_SIZE, rss.rssSize());
                    String bbsTitle = StringUtils.defaultIfBlank(brand.pageTitle(), "BBS 社区");
                    return bbsFinder.getCategoryBySlug(slug)
                            .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "分类不存在")))
                            .flatMap(category -> bbsFinder.listLatestByCategory(slug, size)
                                    .collectList()
                                    .map(items -> RSS2.builder()
                                            .title(category.getDisplayName() + " - " + bbsTitle)
                                            .link(externalUrl)
                                            .description(StringUtils.defaultIfBlank(
                                                    category.getDescription(),
                                                    category.getDisplayName()))
                                            .items(items.stream()
                                                    .map(vo -> toItem(vo, externalUrl))
                                                    .toList())
                                            .build()));
                });
    }

    /** 单条 RSS item：复用 BbsPostVo 已有字段，无需额外查询。 */
    private static RSS2.Item toItem(BbsPostVo vo, String externalUrl) {
        var link = externalUrl + vo.getPermalink();
        var pubDate = vo.getPublishTime() != null
                ? vo.getPublishTime() : vo.getCreationTimestamp();
        return RSS2.Item.builder()
                .title(vo.getTitle())
                .link(link)
                .guid(link)
                // RSS2.Item.description 为 @NotBlank：excerpt 为空须回退到标题，避免校验失败
                .description(StringUtils.defaultIfBlank(vo.getExcerpt(), vo.getTitle()))
                .author(vo.getOwner() != null ? vo.getOwner().getDisplayName() : null)
                .pubDate(pubDate)
                .build();
    }

    private Mono<Brand> brandSetting() {
        return settingFetcher.fetch("appearance", AppearanceSetting.class)
                .map(AppearanceSetting::brandOrEmpty)
                .defaultIfEmpty(new Brand(null, null, null, null));
    }

    private Mono<Rss> rssSetting() {
        return settingFetcher.fetch("browsing", BrowsingSetting.class)
                .map(BrowsingSetting::rssOrEmpty)
                .defaultIfEmpty(new Rss(null, null));
    }
}
