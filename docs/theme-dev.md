# 主题开发指南

> 约定：**忽略未知字段**，接口只增不改；分类 / 作者展示属性已内联，无需二次请求。

本文面向主题开发者。站长安装与配置见 [README](../README.md#快速上手)；完整接口签名也可在 Halo `/swagger-ui.html` 查阅（`BbsV1alpha1Public` / `BbsV1alpha1Console` / `BbsV1alpha1Uc`）。

## 模板覆盖

在主题 `templates/` 下提供同名模板即可覆盖插件默认页：

- `bbs.html` — 列表页
- `bbs_post.html` — 详情页（编辑器预览 `/bbs/preview/{name}` 渲染同一模板，覆盖同样生效）

> BBS 没有独立作者页（无 `bbs_author.html` / `/bbs/u/{username}`）。请在主题作者页（如 `/authors/{name}`）用 Finder 聚合 BBS 数据，或依赖 interaction-plus 用户卡跳转。

## Finder API（`${bbs}`）

```html
<!-- 最新公告 -->
<div th:each="a : ${bbs.listAnnouncements(3)}">
  <a th:href="@{${a.permalink}}" th:text="${a.title}"></a>
</div>

<!-- 帖子分页（默认最后活跃；置顶按作用域浮顶） -->
<div th:each="p : ${bbs.listPosts(1, 10).items}">
  <span th:text="${p.title}"></span>
  <span th:if="${p.category != null}" th:text="${p.category.displayName}"></span>
</div>

<!-- 统一列表入口：分类 / 关键词 / 排序 / 类型 -->
<div th:each="p : ${bbs.list(1, 10, 'tech', null, 'active', 'question').items}">
  <a th:href="@{${p.permalink}}" th:text="${p.title}"></a>
</div>

<!-- 主题作者页：该用户的 BBS 帖子 -->
<div th:each="p : ${bbs.listPostsByOwner(author.metadata.name, 1, 10).items}">
  <a th:href="@{${p.permalink}}" th:text="${p.title}"></a>
</div>

<!-- 分类导航（树） -->
<a th:each="c : ${bbs.listCategoryTree()}"
   th:href="@{/bbs(category=${c.slug})}"
   th:text="|${c.displayName} (${c.totalPostCount})|"></a>
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `listPosts(page, size)` | `Mono<ListResult<BbsPostVo>>` | 已发布内容，默认最后活跃，置顶按作用域浮顶 |
| `listPostsByCategory(slug, page, size)` | `Mono<ListResult<BbsPostVo>>` | 按分类；一级分类含其全部子分类 |
| `listPostsByCategoryAndKeyword(slug, kw, page, size)` | `Mono<ListResult<BbsPostVo>>` | 标题搜索（slug 可空） |
| `list(page, size, categorySlug, keyword, sort)` | `Mono<ListResult<BbsPostVo>>` | 统一入口；`sort`=`active`（默认）/ `latest` / `hot` |
| `list(page, size, categorySlug, keyword, sort, type)` | `Mono<ListResult<BbsPostVo>>` | 同上，另可按 `POST` / `QUESTION` / `ANNOUNCEMENT` 筛选 |
| `listAnnouncements(limit)` | `Flux<BbsPostVo>` | 已发布公告 |
| `listLatest(size)` | `Flux<BbsPostVo>` | 最新（含公告，纯时间倒序，不提权置顶） |
| `listMostReplied(size)` | `Flux<BbsPostVo>` | 全站最多回复的已发布内容（纯评论数排序） |
| `listLatestByCategory(slug, size)` | `Flux<BbsPostVo>` | 某分类树最新（分类 RSS / 时间线） |
| `listPostsByOwner(username, page, size)` | `Mono<ListResult<BbsPostVo>>` | 某作者已发布内容（主题作者页用） |
| `getAuthor(username)` | `Mono<OwnerVo>` | 作者展示信息 |
| `listCategories()` | `Flux<CategoryVo>` | 启用中的分类（树序平铺，含直属与合计帖数） |
| `listCategoryTree()` | `Flux<CategoryVo>` | 启用中的分类树（仅一级，`children` 内嵌） |
| `getBySlug(slug)` | `Mono<BbsPostVo>` | 详情（含净化正文 `content`） |
| `getCategoryBySlug(slug)` | `Mono<CategoryVo>` | 单个分类 |
| `countPosts()` | `Mono<Long>` | 已发布总数（含公告） |

`BbsPostVo` 主要字段：`name` / `title` / `slug` / `type` / `phase` / `pinned` / `pinnedInView` /
`pinPriority` / `locked` / `solved` / `edited`（发布后正文有改动；渲染「已编辑」以此为准）/
`commentsCount` / `excerpt` / `content`（仅详情）/ `permalink` /
`category`（内联 `displayName`/`color`/`icon`/`iconSvg`/`slug`/`parent`/`children`）/
`owner`（内联 `name`/`displayName`/`avatar`）/ `publishTime` / `lastActivityTime` / `lastEditTime`。

> 正文 `content` 写入时已白名单净化，模板可 `th:utext` 输出。
> 列表渲染置顶徽标请用 `pinnedInView`，不要用 `pinned`。

## 公开 REST API（匿名可读）

前缀 `/apis/api.bbs.timxs.com/v1alpha1`：

| 接口 | 说明 |
| --- | --- |
| `GET /posts?page=&size=&categorySlug=&categoryName=&keyword=&sort=&type=` | 已发布分页（`sort`=`active`\|`latest`\|`hot`，默认最后活跃；size 上限 50） |
| `GET /posts/{slug}` | 详情（含正文） |
| `GET /announcements?limit=` | 已发布公告 |
| `GET /categories` | 启用中的分类 |
| `GET /posts/{name}/comments?page=&size=` | 锁定帖的只读评论（`owner.name` 仅 User kind 返回） |
| `GET /comments/{name}/replies?page=&size=` | 某评论的只读楼中楼 |

## 数据模型

- **BbsPost**（`bbs.timxs.com/v1alpha1`）：标题、slug、类型（公告 / 讨论 / 问答）、分类、摘要与业务状态；正文完整复用 Halo 核心 **Snapshot** GVK，通过 `baseSnapshot` / `headSnapshot` / `releaseSnapshot` 区分差异基线、工作版本和前台版本。`spec.draft` 只保存已发布内容的工作稿元数据与审核状态（不复制正文）；此外还包含置顶 + 权重、锁定、已解决、软删除、发布时间、最后活跃时间、最后编辑时间、作者
- **BbsModerationRecord**（`bbs.timxs.com/v1alpha1`）：只追加的提交、发布、通过、驳回、撤稿与取消发布审计事件；历史恢复经提交 / 发布事件附原因记录；每条记录绑定当时的 Snapshot（仅展示参考），历史版本删除遵循官方规则（仅基线与发布中版本不可删），不因审核记录引用而额外保护
- **BbsCategory**：名称、slug（唯一）、描述、Iconify（含离线 SVG，颜色烤在 SVG 里）、分类色、父分类、封面、排序、启用、置顶帖上首页（仅一级）、版主角色（仅一级）

卸载时可在 Console 勾选「同时删除数据」清理自定义模型。
