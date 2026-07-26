# BBS 社区（plugin-bbs）

Halo 2.x 插件：**置顶公告 + 社区帖子 + 分类**，适合游戏社区 / 站点公告场景（如 CS1.6、DNF、插件专区等分类）。

## 功能特性

- **置顶公告**：管理员发布公告，前台顶部公告区展示，支持置顶权重排序
- **普通帖子**：支持草稿 / 发布两态，富文本编辑（官方编辑器，支持图片上传）
- **分类**：Iconify 图标（保存时解析为离线 SVG，前台零外部依赖渲染）+ 主题色 + 自定义别名（slug），前台分类导航过滤
- **Console 管理端**：贴合 Halo 设计语言（VPageHeader / VEntity / FilterDropdown），服务端筛选、批量删除、快捷置顶 / 发布切换
- **用户中心（UC）**：登录用户管理自己的帖子（发帖 / 编辑 / 删除，仅能操作自己的内容）
- **发帖审核（可选）**：开启后用户发帖 / 编辑须管理员审核，后台一键通过 / 驳回（可附驳回原因，作者在用户中心可见）
- **评论讨论**：接入 Halo 官方评论体系——帖子详情页自带评论区（评论 + 回复两级），后台复用 Halo 评论管理
- **全文搜索**：接入 Halo 搜索体系——站点全局搜索（主题搜索框 / 搜索页）可按标题与正文搜到已发布帖子，结果直达帖子页
- **RSS 订阅**：`/bbs/rss.xml` 输出最新已发布内容（RSS 2.0，含公告，时间倒序）
- **阅读体验**：长帖自动目录（正文标题 ≥3 个才出现）、正文图片点击放大灯箱、详情页 SEO/OG meta、同分类相关推荐
- **作者页**：`/bbs/u/{用户名}` 展示某作者的全部已发布内容
- **前台访问页**：`/bbs` 现代化默认页面（深浅色自适应、响应式），主题可整页覆盖
- **面向主题开放接口**：Finder API（模板变量 `${bbs}`）+ 匿名可读的公开 REST API
- **安全**：正文 HTML 服务端白名单净化（与 Halo 评论系统同款 Safelist），防 XSS

## 环境要求

- Halo `>= 2.25.0`
- 构建：JDK 21、Node 18+、pnpm

## 构建

```bash
./gradlew build
```

产物在 `build/libs/plugin-bbs-*.jar`，在 Console「插件」页上传安装即可。

前端单独调试：

```bash
cd ui
pnpm install
pnpm dev          # watch 构建
pnpm type-check   # vue-tsc 类型检查
```

## 使用说明

### Console（后台）

「BBS 社区」菜单（内容分组）：

- **帖子列表**：按状态 / 类型 / 分类 / 关键词筛选，行内操作（编辑 / 设置 / 置顶 / 发布 / 删除），支持批量删除
- **写帖子 / 公告**：全屏富文本编辑器（对标官方文章编辑器），「设置」中可选类型（公告 / 帖子）、分类、置顶与权重、别名、摘要
- **分类管理**：由帖子列表页「分类」按钮进入，Iconify 图标选择器 + 主题色磁贴

### 用户中心（UC）

「我的帖子」：登录用户发帖、编辑、删除自己的帖子（**发帖必须登录**，匿名访客只能浏览）。用户帖固定为普通帖子类型，不能置顶。默认发布即生效；在插件设置开启「用户发帖需审核」后，发布 / 编辑将进入待审核状态，管理员在后台通过后才会展示。

### 前台

- 列表页：`/bbs`，支持 `?category={slug}`（分类过滤）、`?q=`（标题搜索）、`?page=`（分页）
- 详情页：`/bbs/post/{slug}`，正文下方为评论区与同分类推荐
- 作者页：`/bbs/u/{用户名}`；RSS：`/bbs/rss.xml`

### 评论

帖子评论走 Halo 官方评论体系（插件实现了 `CommentSubject`）：

- **前台渲染需安装官方「评论组件」插件（plugin-comment-widget）**，未安装时评论区为空、不影响页面；
- **是否需要登录、是否审核，由「系统设置 → 评论」统一控制**（如「仅允许注册用户评论」「新评论需要审核」），与文章评论策略一致；
- 评论管理复用 Halo 后台的「评论」页面。

### 插件设置

- **基础**（列表行为）：每页条数、首页公告条数
- **外观**（品牌与 Hero）：前台页面标题、社区 Logo、副标题、前台主题色、Hero 背景（纯色 / 图片 Banner）、统计信息开关（副标题与统计可同时展示）
- **审核**：「用户发帖需审核」开关

## 权限模型（角色模板）

| 角色模板 | 说明 | 默认授予 |
| --- | --- | --- |
| BBS 社区查看 (`bbs-view`) | Console 只读（帖子 / 分类列表） | 需手动分配 |
| BBS 社区管理 (`bbs-manage`) | 帖子 / 公告 / 分类全部管理能力（依赖查看） | 需手动分配（超管天然拥有） |
| BBS 社区发帖 (`bbs-uc-post`) | 用户中心管理自己的帖子 | 聚合到所有登录用户 |
| 公开读 (`bbs-public-read`) | 前台读取已发布内容 | 聚合到匿名 + 登录用户（隐藏） |

如不希望所有注册用户都能发帖，删除 `roleTemplate.yaml` 中 `bbs-uc-post` 的
`rbac.authorization.halo.run/aggregate-to-authenticated` 标签后重新构建，再手动把该角色分配给指定用户。

## 面向主题开发者

> 约定：**消费方须忽略未知字段**，接口演进只增不改；展示所需属性（分类名 / 颜色 / 图标、作者显示名 / 头像）均已内联在返回值中，无需二次请求。

### 1. 模板覆盖

在主题 `templates/` 下提供同名模板即可整页覆盖插件默认页面：

- `bbs.html` — 列表页
- `bbs_post.html` — 详情页
- `bbs_author.html` — 作者页

### 2. Finder API（模板变量 `${bbs}`）

任何主题模板中可直接调用：

```html
<!-- 最新公告（最多 3 条） -->
<div th:each="a : ${bbs.listAnnouncements(3)}">
  <a th:href="@{${a.permalink}}" th:text="${a.title}"></a>
</div>

<!-- 帖子分页（第 1 页，每页 10 条，置顶优先） -->
<div th:each="p : ${bbs.listPosts(1, 10).items}">
  <span th:text="${p.title}"></span>
  <span th:if="${p.category != null}" th:text="${p.category.displayName}"></span>
  <span th:if="${p.owner != null}" th:text="${p.owner.displayName}"></span>
</div>

<!-- 分类列表（含已发布帖子数） -->
<a th:each="c : ${bbs.listCategories()}"
   th:href="@{/bbs(category=${c.slug})}"
   th:text="|${c.icon} ${c.displayName} (${c.postCount})|"></a>
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `listPosts(page, size)` | `ListResult<BbsPostVo>` | 已发布普通帖子，置顶优先 |
| `listPostsByCategory(slug, page, size)` | `ListResult<BbsPostVo>` | 按分类 slug 过滤 |
| `listPostsByCategoryAndKeyword(slug, kw, page, size)` | `ListResult<BbsPostVo>` | 标题关键词搜索（slug 可传空串） |
| `listAnnouncements(limit)` | `Flux<BbsPostVo>` | 已发布公告，置顶权重倒序 |
| `listCategories()` | `Flux<CategoryVo>` | 启用中的分类，priority 升序 |
| `getBySlug(slug)` | `Mono<BbsPostVo>` | 详情（含净化后的正文 HTML `content`） |
| `getCategoryBySlug(slug)` | `Mono<CategoryVo>` | 单个分类 |
| `countPosts()` | `Mono<Long>` | 已发布总数（含公告） |
| `listLatest(size)` | `Flux<BbsPostVo>` | 最新已发布内容（含公告，纯时间倒序） |
| `listPostsByOwner(username, page, size)` | `ListResult<BbsPostVo>` | 某作者的已发布内容 |
| `getAuthor(username)` | `Mono<OwnerVo>` | 作者展示信息（显示名 / 头像） |

`BbsPostVo` 关键字段：`name` / `title` / `slug` / `type`(ANNOUNCEMENT|POST) / `pinned` /
`excerpt` / `content`(仅详情) / `permalink` / `category`(内联 `displayName`/`color`/`icon`/`iconSvg`/`slug`) /
`owner`(内联 `displayName`/`avatar`) / `publishTime`。

> 正文 `content` 已在写入时做服务端白名单净化，模板中可直接 `th:utext` 输出。

### 3. 公开 REST API（匿名可读）

前缀 `/apis/api.bbs.timxs.com/v1alpha1`，供主题 JS / 第三方前端调用：

| 接口 | 说明 |
| --- | --- |
| `GET /posts?page=&size=&categorySlug=&categoryName=&keyword=` | 已发布普通帖子分页（置顶优先，size 上限 50） |
| `GET /posts/{slug}` | 帖子详情（含正文） |
| `GET /announcements?limit=` | 已发布公告 |
| `GET /categories` | 启用中的分类（含已发布帖子数） |

完整的接口文档（含 Console / UC 端点）可在 Halo 的 `/swagger-ui.html` 中查看
（分组 `BbsV1alpha1Public` / `BbsV1alpha1Console` / `BbsV1alpha1Uc`）。

## 数据模型

- `BbsPost`（bbs.timxs.com/v1alpha1）：标题、别名（唯一）、类型（公告 / 帖子）、分类、正文（净化后 HTML）、摘要、置顶 + 权重、状态（草稿 / 待审核 / 已发布 / 已驳回）、发布时间、作者
- `BbsCategory`：名称、别名（唯一）、描述、Iconify 图标（含离线 SVG）、主题色（HEX 校验）、排序、启用开关

卸载插件时可在 Console 勾选「同时删除数据」清理上述自定义模型数据。

## License

GPL-3.0
