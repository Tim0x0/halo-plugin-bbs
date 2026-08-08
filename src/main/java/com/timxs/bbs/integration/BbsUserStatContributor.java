package com.timxs.bbs.integration;

import com.timxs.bbs.query.BbsQueryService;
import com.timxs.interactionplus.api.UserStat;
import com.timxs.interactionplus.api.UserStatContributor;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 向 interaction-plus 贡献 BBS 发帖数（用户悬浮卡数据行 / 公开身份 stats.extras）。
 *
 * <p><b>刻意不标 {@code @Component}</b>：Halo 启动插件时会对 components.idx 里的每个类
 * 无条件 loadClass，而实现类的父接口在类加载期即被解析——interaction-plus 缺席时该接口
 * 不在 classpath，会以 NoClassDefFoundError 拖垮本插件启动。本类由
 * {@link InteractionPlusIntegration} 在确认 api 类可加载后以 @Bean 方式注册。</p>
 *
 * <p>配套的 ExtensionDefinition 见 {@code extensions/userStatContributor.yaml}（必须声明，
 * 来源插件 id 由系统按 ED 归属盖章，缺失时贡献项被整体忽略）。</p>
 *
 * @author Tim0x0
 */
@RequiredArgsConstructor
public class BbsUserStatContributor implements UserStatContributor {

    private final BbsQueryService queryService;

    @Override
    public Flux<UserStat> getUserStats(String userName) {
        // 口径：已发布 + 作者（含公告），与 Finder listPostsByOwner / getAuthor 一致
        return queryService.countPublishedByOwner(userName)
                .map(count -> new UserStat("posts", "发帖", String.valueOf(count)))
                .flux()
                // 契约要求失败降级为空流（不要 error），避免消耗聚合侧容错预算
                .onErrorResume(e -> Flux.empty());
    }
}
