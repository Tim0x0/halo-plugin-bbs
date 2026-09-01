package com.timxs.bbs.integration;

import com.timxs.bbs.finder.BbsFinder;
import com.timxs.bbs.service.BbsSettings;
import com.timxs.bbs.service.BbsSettings.Brand;
import com.timxs.bbs.service.BbsSettings.Rss;
import com.timxs.bbs.vo.BbsPostVo;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.feed.RSS2;
import run.halo.feed.RssRouteItem;

/**
 * 把 BBS 社区最新帖子与公告作为 RSS 源贡献给 plugin-feed。
 *
 * <p>不在本类标 {@code @Component}：plugin-feed 缺席时父接口 {@link RssRouteItem}
 * 不在 classpath，进 components.idx 会在启动期 {@link NoClassDefFoundError}；改由
 * {@link BbsFeedIntegration} 在确认 api 类可加载后以 {@code @Bean} 注册。</p>
 *
 * <p>输出路由由 plugin-feed 按 {@code /feed/{namespace}/{pathPattern}} 注册为
 * {@code /feed/bbs/posts.xml}；编码（{@code application/xml} + UTF-8）由 plugin-feed 统一处理，
 * 杜绝旧自托管端点 {@code application/rss+xml} 无 charset 导致的中文乱码。</p>
 *
 * <p>{@code channel.link} 用站点根 URL（{@link ExternalUrlSupplier}），与官方 plugin-feed /
 * plugin-moments 一致——plugin-feed 会据此拼 {@code <atom:link rel="self">}（channel.link + 请求路径），
 * 故必须为站点根，否则 self 会拼成 {@code /bbs/feed/bbs/posts.xml} 之类的错误路径。</p>
 *
 * <p>handler 不吞错成 empty：plugin-feed 的 {@code RssCacheManager} 用 {@code doOnNext} 取 RSS2，
 * empty 会导致其 {@code rss2==null} 而 NPE；错误应直接传播（对齐官方 plugin-moments）。</p>
 *
 * <p>设置经 {@link BbsSettings} 读取：{@code appearance.brand} 取标题/副标题，
 * {@code browsing.rss} 取条数。</p>
 *
 * @author Tim0x0
 */
@RequiredArgsConstructor
public class BbsRssRouteItem implements RssRouteItem {

    private static final int DEFAULT_RSS_SIZE = 20;
    private static final int MAX_RSS_SIZE = 100;

    private final BbsFinder bbsFinder;
    private final BbsSettings settings;
    private final ExternalUrlSupplier externalUrlSupplier;

    @Override
    public Mono<String> pathPattern() {
        // RSS 是否暴露完全由 plugin-feed 是否安装启用决定（对齐官方 plugin-moments，无额外开关）
        return Mono.just("posts.xml");
    }

    @Override
    public String displayName() {
        return "BBS 社区";
    }

    @Override
    public String description() {
        return "订阅 BBS 社区最新发布的帖子与公告";
    }

    @Override
    public String namespace() {
        return "bbs";
    }

    @Override
    public String example() {
        return "/feed/bbs/posts.xml";
    }

    @Override
    public Mono<RSS2> handler(ServerRequest request) {
        var externalUrl = externalUrlSupplier
                .getURL(request.exchange().getRequest()).toString();
        return Mono.zip(brandSetting(), rssSetting())
                .flatMap(t -> {
                    var brand = t.getT1();
                    var rss = t.getT2();
                    int size = (rss.rssSize() == null || rss.rssSize() < 1)
                            ? DEFAULT_RSS_SIZE : Math.min(MAX_RSS_SIZE, rss.rssSize());
                    String title = StringUtils.defaultIfBlank(brand.pageTitle(), "BBS 社区");
                    return bbsFinder.listLatest(size)
                            .collectList()
                            .map(items -> RSS2.builder()
                                    .title(title)
                                    .link(externalUrl)
                                    .description(StringUtils.defaultIfBlank(brand.slogan(), title))
                                    .items(items.stream()
                                            .map(vo -> toItem(vo, externalUrl))
                                            .toList())
                                    .build());
                });
    }

    /** 单条 RSS item：复用 BbsPostVo 已有字段，无需额外查询（与旧 buildRss 同源）。 */
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
        return settings.appearance().map(BbsSettings.Appearance::brand);
    }

    private Mono<Rss> rssSetting() {
        return settings.browsing().map(BbsSettings.Browsing::rss);
    }
}
