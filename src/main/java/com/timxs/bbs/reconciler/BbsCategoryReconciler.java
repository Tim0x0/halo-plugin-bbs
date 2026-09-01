package com.timxs.bbs.reconciler;

import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.or;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.service.BbsModerationScope;
import com.timxs.bbs.service.HtmlSanitizer;
import com.timxs.bbs.util.BbsUrls;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * 分类调和器：维护 {@code status.permalink}，并纠正各写入路径（表单 / CRUD PATCH）
 * 可能留下的脏数据——两级封顶、图标 SVG 与封面 URL 消毒、子分类不得持有
 * {@code pinToHome}。
 *
 * <p>把前台地址由服务端下发，前端与主题不必再各自硬拼 {@code /bbs?category=xxx}——
 * 路由一旦调整，改这一处即可。</p>
 *
 * <p>帖子数不在这里算：它由帖子变更驱动（见 {@link BbsPostReconciler}），
 * 分类自身的变更不会改变其帖子数。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsCategoryReconciler implements Reconciler<Reconciler.Request> {

    private static final String FINALIZER = "bbs.timxs.com/category";

    private final ExtensionClient client;
    private final BbsModerationScope moderationScope;

    public BbsCategoryReconciler(ExtensionClient client, BbsModerationScope moderationScope) {
        this.client = client;
        this.moderationScope = moderationScope;
    }

    @Override
    public Result reconcile(Request request) {
        // 包含缺失/删除事件：分类授权或层级任何变化都必须立即使安全拓扑失效。
        moderationScope.invalidateCategoryCache();
        client.fetch(BbsCategory.class, request.name()).ifPresent(category -> {
            if (ExtensionUtil.isDeleted(category)) {
                // 对齐 Halo CategoryReconciler：删除前解除所有关联，全部成功后才放
                // finalizer。BBS 用 child.parentName 表示层级，因此还需显式提升子分类。
                liftChildren(request.name());
                detachPosts(request.name());
                if (category.getMetadata().getFinalizers() != null
                        && category.getMetadata().getFinalizers().contains(FINALIZER)) {
                    OptimisticUpdates.update(client, BbsCategory.class, request.name(), latest -> {
                        var finals = latest.getMetadata().getFinalizers();
                        if (finals != null) {
                            finals.remove(FINALIZER);
                        }
                    });
                }
                return;
            }
            if (category.getMetadata().getFinalizers() == null
                    || !category.getMetadata().getFinalizers().contains(FINALIZER)) {
                OptimisticUpdates.update(client, BbsCategory.class, request.name(), latest ->
                        ExtensionUtil.addFinalizers(latest.getMetadata(), Set.of(FINALIZER)));
            }
            var permalink = BbsUrls.categoryPermalink(category.getSpec().getSlug());
            var status = category.getStatus();
            if (status == null) {
                status = new BbsCategory.Status();
                category.setStatus(status);
            }
            boolean dirty = false;
            // 两级封顶：父级自身还有父时拆掉这段脏父子关系，避免 A←B←C
            var parentName = category.getSpec().getParentName();
            if (StringUtils.isNotBlank(parentName)) {
                var parent = client.fetch(BbsCategory.class, parentName).orElse(null);
                if (parent == null
                        || ExtensionUtil.isDeleted(parent)
                        || StringUtils.isNotBlank(parent.getSpec().getParentName())
                        || parentName.equals(category.getMetadata().getName())) {
                    category.getSpec().setParentName(null);
                    dirty = true;
                }
            }
            if (!Objects.equals(status.getPermalink(), permalink)) {
                status.setPermalink(permalink);
                dirty = true;
            }
            if (needsSanitize(category.getSpec())) {
                dirty = true;
            }
            // 二级分类不得开「置顶帖上首页」、不得配版主角色：两者都是板块级配置，
            // 只有一级分类可设。必须放在两级封顶纠偏之后——刚被纠成一级的分类，
            // 这两项重新合法
            if (StringUtils.isNotBlank(category.getSpec().getParentName())
                    && (Boolean.TRUE.equals(category.getSpec().getPinToHome())
                            || BbsModerationScope.hasModerators(category))) {
                dirty = true;
            }
            if (dirty) {
                OptimisticUpdates.update(client, BbsCategory.class, request.name(), latest -> {
                    var latestStatus = latest.getStatus();
                    if (latestStatus == null) {
                        latestStatus = new BbsCategory.Status();
                        latest.setStatus(latestStatus);
                    }
                    latestStatus.setPermalink(BbsUrls.categoryPermalink(latest.getSpec().getSlug()));
                    sanitizeSpec(latest);
                    var latestParent = latest.getSpec().getParentName();
                    if (StringUtils.isNotBlank(latestParent)) {
                        var parent = client.fetch(BbsCategory.class, latestParent).orElse(null);
                        if (parent == null
                                || ExtensionUtil.isDeleted(parent)
                                || StringUtils.isNotBlank(parent.getSpec().getParentName())
                                || latestParent.equals(latest.getMetadata().getName())) {
                            latest.getSpec().setParentName(null);
                        }
                    }
                    // 纠偏后仍是子分类的，收回板块级特权（上首页 / 版主授权），
                    // 管辖由父分类的配置统一覆盖整棵树
                    if (StringUtils.isNotBlank(latest.getSpec().getParentName())) {
                        latest.getSpec().setPinToHome(false);
                        latest.getSpec().setModeratorRoles(null);
                    }
                });
            }
        });
        return Result.doNotRetry();
    }

    private static boolean needsSanitize(BbsCategory.Spec spec) {
        var svg = spec.getIconSvg();
        if (StringUtils.isNotBlank(svg) && !svg.equals(HtmlSanitizer.cleanSvg(svg))) {
            return true;
        }
        var cover = spec.getCover();
        if (StringUtils.isNotBlank(cover) && !cover.equals(BbsUrls.sanitize(cover))) {
            return true;
        }
        return false;
    }

    private static void sanitizeSpec(BbsCategory category) {
        var spec = category.getSpec();
        spec.setIconSvg(HtmlSanitizer.cleanSvg(spec.getIconSvg()));
        spec.setCover(BbsUrls.sanitize(spec.getCover()));
    }

    /**
     * 父分类被删：先冻结全部子分类名称，再逐一提升为一级。
     *
     * <p>不能边翻页边改 {@code parentName}：已更新对象会退出查询集合，使后续页前移并
     * 跳项。名称全部处理成功后调用方才会移除 finalizer；中途失败则由控制器重试。</p>
     */
    private void liftChildren(String parentName) {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.parentName", parentName))
                .build();
        client.listAllNames(BbsCategory.class, options,
                        Sort.by(Sort.Order.asc("metadata.name")))
                .forEach(childName -> OptimisticUpdates.update(
                        client, BbsCategory.class, childName,
                        latest -> latest.getSpec().setParentName(null)));
    }

    /**
     * 删除分类前解除帖子关联；发布副本与工作稿都要处理，避免前台或下次发布重新引用
     * 已删除分类。历史 Snapshot 保留原分类信息作为版本审计，恢复后由帖子调和器纠偏。
     */
    private void detachPosts(String categoryName) {
        var options = ListOptions.builder()
                .fieldQuery(or(
                        equal("spec.categoryName", categoryName),
                        equal("spec.draft.categoryName", categoryName)))
                .build();
        client.listAllNames(BbsPost.class, options,
                        Sort.by(Sort.Order.asc("metadata.name")))
                .forEach(postName -> OptimisticUpdates.update(
                        client, BbsPost.class, postName, latest -> {
                            var spec = latest.getSpec();
                            if (Objects.equals(categoryName, spec.getCategoryName())) {
                                spec.setCategoryName(null);
                            }
                            var draft = spec.getDraft();
                            if (draft != null
                                    && Objects.equals(categoryName, draft.getCategoryName())) {
                                draft.setCategoryName(null);
                            }
                        }));
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new BbsCategory())
                .build();
    }
}
