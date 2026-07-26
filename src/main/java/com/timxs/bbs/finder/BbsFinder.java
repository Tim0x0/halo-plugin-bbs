package com.timxs.bbs.finder;

import com.timxs.bbs.query.BbsQueryService;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import com.timxs.bbs.vo.OwnerVo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.theme.finders.Finder;

/**
 * 前台主题可调用的BBS 社区数据查询器，模板变量名 {@code ${bbs}}。
 *
 * <p>只服务前台：所有列表均只含已发布内容；VO 自包含（分类 / 作者展示属性内联），
 * 主题拿到即可直接渲染。消费方应忽略未知字段（后续演进只增不改）。</p>
 *
 * <p>模板用法示例：</p>
 * <pre>{@code
 * <div th:each="a : ${bbs.listAnnouncements(3)}" th:text="${a.title}"></div>
 * <div th:each="p : ${bbs.listPosts(1, 10).items}" th:text="${p.title}"></div>
 * }</pre>
 *
 * @author Tim0x0
 */
@Finder("bbs")
public class BbsFinder {

    private final BbsQueryService queryService;

    public BbsFinder(BbsQueryService queryService) {
        this.queryService = queryService;
    }

    /** 已发布普通帖子分页（置顶优先，发布时间倒序）。 */
    public Mono<ListResult<BbsPostVo>> listPosts(int page, int size) {
        return queryService.listPublicPosts(page, size, null, null, null);
    }

    /** 按分类 slug 过滤的已发布帖子分页。 */
    public Mono<ListResult<BbsPostVo>> listPostsByCategory(String categorySlug, int page,
            int size) {
        return queryService.listPublicPosts(page, size, null, categorySlug, null);
    }

    /** 标题关键词搜索（可叠加分类 slug 过滤，slug 传空表示全部分类）。 */
    public Mono<ListResult<BbsPostVo>> listPostsByCategoryAndKeyword(String categorySlug,
            String keyword, int page, int size) {
        return queryService.listPublicPosts(page, size, null, categorySlug, keyword);
    }

    /** 已发布公告（置顶权重与发布时间倒序），limit 为最大条数。 */
    public Flux<BbsPostVo> listAnnouncements(int limit) {
        return queryService.listAnnouncements(limit);
    }

    /** 最新已发布内容（含公告，纯发布时间倒序，不做置顶提权）——RSS / 时间线场景。 */
    public Flux<BbsPostVo> listLatest(int size) {
        return queryService.listLatestPublished(size);
    }

    /** 某作者的已发布内容分页（含公告，发布时间倒序）。 */
    public Mono<ListResult<BbsPostVo>> listPostsByOwner(String username, int page, int size) {
        return queryService.listPublicByOwner(username, page, size);
    }

    /** 作者展示信息（显示名 / 头像；用户不存在时以用户名兜底）。 */
    public Mono<OwnerVo> getAuthor(String username) {
        return queryService.getAuthor(username);
    }

    /** 启用中的分类（priority 升序，含已发布帖子数）。 */
    public Flux<CategoryVo> listCategories() {
        return queryService.listCategories(true);
    }

    /** 按 slug 取已发布帖子详情（含净化后的正文 HTML）。 */
    public Mono<BbsPostVo> getBySlug(String slug) {
        return queryService.getPublishedBySlug(slug);
    }

    /** 按 slug 取启用中的分类。 */
    public Mono<CategoryVo> getCategoryBySlug(String slug) {
        return queryService.getCategoryBySlug(slug);
    }

    /** 已发布帖子总数（含公告）。 */
    public Mono<Long> countPosts() {
        return queryService.countPublished();
    }
}
