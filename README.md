# BBS 社区（plugin-bbs）

Halo 2.x 插件：**公告 + 社区帖子 + 分类**，适合技术社区 / 站点公告等场景（如 技术分享、问答、公告等分类）。

## 功能特性

- **公告**：管理员发布的官方帖子，与普通帖混排、带「公告」标识；**可不挂分类**（全站公告，任意分类视图均可见），也可指定分类
- **置顶**：与类型正交的独立开关（公告 / 普通帖均可置顶）；置顶帖排在列表顶部，权重控制先后
- **普通帖子**：草稿 / 待审核 / 已发布 / 已驳回；富文本编辑（官方编辑器，支持图片上传）
- **分类**：Iconify 图标（保存时解析为离线 SVG）+ 主题色 + slug，前台侧栏 / 移动端下拉过滤
- **Console**：贴合 Halo 设计语言，服务端筛选、批量删除、快捷置顶 / 发布、审核通过 / 驳回
- **用户中心（UC）**：登录用户管理自己的帖子（发帖 / 编辑 / 删除，仅能操作自己的内容）
- **发帖审核（可选）**：用户发帖 / 编辑须管理员审核；可配置「编辑已发布内容是否重新审核」
- **评论**：接入 Halo 官方评论体系；详情页评论区由「评论组件」插件渲染
- **全文搜索**：接入 Halo 搜索，已发布帖子可被站点全局搜索
- **RSS**：经 plugin-feed 在 `/feed/bbs/posts.xml` 输出（RSS 2.0，含公告，时间倒序；需安装 plugin-feed）
- **前台版式**：对齐 Flarum——白顶栏 + 品牌色 Hero、全页白底无卡片列表、分类 chip、公告/置顶圆形徽标、帖子流（楼主第一楼 + 评论区融入）、目录（桌面右栏）、分享复制链接、页脚声明与站点版权
- **排序**：`?sort=latest`（默认，发布时间）/ `?sort=hot`（评论数，置顶仍优先）
- **作者入口**：不提供独立 `/bbs/u` 作者页；作者名按「作者链接模板」跳转（默认主题作者页 `/authors/{name}`）；可选接入 **interaction-plus**（装扮展示 + 用户卡链接优先）
- **面向主题**：Finder（`${bbs}`）+ 公开 REST API；主题可覆盖 `bbs.html` / `bbs_post.html`，或在主题作者页用 Finder 拉取某用户的帖子
- **安全**：正文 HTML 服务端白名单净化，防 XSS

## 环境要求

- Halo `>= 2.25.0`
- 构建：JDK 21、Node 18+、pnpm
- 可选：`interaction-plus` `>= 0.1.0-alpha.7`（装扮与用户卡链接；未安装时 BBS 照常运行）
- 可选：`PluginFeed` `>= 1.4.0`（RSS 订阅 `/feed/bbs/posts.xml`；未安装时 BBS 无 RSS，其余照常）

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

- **帖子列表**：按状态 / 类型 / 分类 / 关键词筛选，行内操作（编辑 / 设置 / 置顶 / 发布 / 删除 / 审核），支持批量删除
- **写帖子 / 公告**：全屏富文本编辑器；设置中可选类型、分类（公告可留空=全站公告）、置顶与权重、别名、摘要
- **分类管理**：由帖子列表页「分类」进入，Iconify 图标 + 主题色

### 用户中心（UC）

「我的帖子」：登录用户发帖、编辑、删除自己的帖子（**发帖必须登录**）。用户帖为普通帖子类型，不能置顶。默认发布即生效；开启「用户发帖需审核」后进入待审核，管理员通过后才会展示。

### 前台

| 路径 | 说明 |
| --- | --- |
| `/bbs` | 列表。查询参数：`category`（分类 slug）、`q`（标题关键词）、`page`、`sort`（`latest` \| `hot`） |
| `/bbs/post/{slug}` | 详情：楼主流 + 评论区 + 相关推荐；桌面右栏目录（正文 h2/h3 ≥ 3） |
| `/feed/bbs/posts.xml` | RSS 2.0（由 plugin-feed 提供，需安装 plugin-feed） |

**作者名链接**（无独立 BBS 作者页）：

1. 插件设置「作者链接模板」（默认 `/authors/{name}`，`{name}` = 用户名；**留空 = 作者名不跳转**）
2. 若开启「接入互动增强」，且 interaction-plus 的「用户卡跳转链接」非空 → **优先用对方的模板**（与名片跳转一致）
3. 互动增强未安装 / 未就绪 / 模板为空 → **静默回退** BBS 作者链接模板，不报错

主题作者页若要展示该用户的 BBS 帖子，使用 Finder：`listPostsByOwner` / `getAuthor`（见下文）。

### 评论

- 前台需安装官方 **评论组件**（plugin-comment-widget）；未安装时评论区为空、不影响页面
- 登录 / 审核策略由「系统设置 → 评论」统一控制
- 评论管理复用 Halo 后台「评论」页

### 互动增强（可选）

插件设置 → **互动增强**：

| 项 | 说明 |
| --- | --- |
| **接入互动增强** | 需已安装并启用 interaction-plus。开启后：① 前台装扮（头像框 / 称号 / 勋章 / 悬浮名片）；② 作者名链接优先用其「用户卡跳转链接」。关闭则不加载装扮，作者名仅用下方模板 |
| **作者链接模板** | 兜底跳转，默认 `/authors/{name}`；留空表示作者名不可点 |

列表页昵称不渲染完整身份行（密度），仅昵称样式 + 最高优先级身份标识；详情楼主仍可用完整身份行。发帖数可通过扩展点贡献到 interaction-plus 名片统计（插件在 classpath 时自动注册）。

### 插件设置

| 分组 | 内容 |
| --- | --- |
| **基础** | 前台标题、Logo、副标题、主题色、Hero（纯色 / Banner）、统计开关、页脚声明、每页条数 |
| **审核** | 用户发帖需审核；编辑已发布是否重新审核 |
| **互动增强** | 接入开关、作者链接模板 |

页脚：声明文案（可清空隐藏）+ `© {年} {站点名}`（站点名来自 Halo 系统设置，链到博客首页）+ Powered by bbs（GitHub）。

## 权限模型（角色模板）

| 角色模板 | 说明 | 默认授予 |
| --- | --- | --- |
| BBS 社区查看 (`bbs-view`) | Console 只读 | 需手动分配 |
| BBS 社区管理 (`bbs-manage`) | 帖子 / 公告 / 分类全部管理（依赖查看） | 需手动分配（超管天然拥有） |
| BBS 社区发帖 (`bbs-uc-post`) | 用户中心管理自己的帖子 | 聚合到所有登录用户 |
| 公开读 (`bbs-public-read`) | 前台读取已发布内容 | 聚合到匿名 + 登录用户（隐藏） |

如不希望所有注册用户都能发帖，删除 `roleTemplate.yaml` 中 `bbs-uc-post` 的
`rbac.authorization.halo.run/aggregate-to-authenticated` 标签后重新构建，再手动分配该角色。

## 面向主题开发者

> 约定：**忽略未知字段**，接口只增不改；分类 / 作者展示属性已内联，无需二次请求。

### 1. 模板覆盖

在主题 `templates/` 下提供同名模板即可覆盖插件默认页：

- `bbs.html` — 列表页
- `bbs_post.html` — 详情页

> 已不再提供 `bbs_author.html` / `/bbs/u/{username}`。请在主题作者页（如 `/authors/{name}`）用 Finder 聚合 BBS 数据，或依赖 interaction-plus 用户卡跳转。

### 2. Finder API（`${bbs}`）

```html
<!-- 最新公告 -->
<div th:each="a : ${bbs.listAnnouncements(3)}">
  <a th:href="@{${a.permalink}}" th:text="${a.title}"></a>
</div>

<!-- 帖子分页（置顶优先；分类视图含全站公告） -->
<div th:each="p : ${bbs.listPosts(1, 10).items}">
  <span th:text="${p.title}"></span>
  <span th:if="${p.category != null}" th:text="${p.category.displayName}"></span>
</div>

<!-- 统一列表入口：分类 / 关键词 / 排序（sort 传 hot 为热门） -->
<div th:each="p : ${bbs.list(1, 10, 'tech', null, 'hot').items}">
  <a th:href="@{${p.permalink}}" th:text="${p.title}"></a>
</div>

<!-- 主题作者页：该用户的 BBS 帖子 -->
<div th:each="p : ${bbs.listPostsByOwner(author.metadata.name, 1, 10).items}">
  <a th:href="@{${p.permalink}}" th:text="${p.title}"></a>
</div>

<!-- 分类导航 -->
<a th:each="c : ${bbs.listCategories()}"
   th:href="@{/bbs(category=${c.slug})}"
   th:text="|${c.displayName} (${c.postCount})|"></a>
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `listPosts(page, size)` | `ListResult<BbsPostVo>` | 已发布内容，置顶优先 |
| `listPostsByCategory(slug, page, size)` | `ListResult<BbsPostVo>` | 按分类；含该分类帖 + 未选分类的全站公告 |
| `listPostsByCategoryAndKeyword(slug, kw, page, size)` | `ListResult<BbsPostVo>` | 标题搜索（slug 可空） |
| `list(page, size, categorySlug, keyword, sort)` | `ListResult<BbsPostVo>` | 统一入口；`sort=hot` 按评论数（置顶仍优先） |
| `listAnnouncements(limit)` | `Flux<BbsPostVo>` | 已发布公告 |
| `listLatest(size)` | `Flux<BbsPostVo>` | 最新（含公告，纯时间倒序，不提权置顶） |
| `listPostsByOwner(username, page, size)` | `ListResult<BbsPostVo>` | 某作者已发布内容（主题作者页用） |
| `getAuthor(username)` | `Mono<OwnerVo>` | 作者展示信息 |
| `listCategories()` | `Flux<CategoryVo>` | 启用中的分类 |
| `getBySlug(slug)` | `Mono<BbsPostVo>` | 详情（含净化正文 `content`） |
| `getCategoryBySlug(slug)` | `Mono<CategoryVo>` | 单个分类 |
| `countPosts()` | `Mono<Long>` | 已发布总数（含公告） |

`BbsPostVo` 主要字段：`name` / `title` / `slug` / `type` / `phase` / `pinned` / `pinPriority` /
`commentCount` / `excerpt` / `content`（仅详情）/ `permalink` /
`category`（内联 `displayName`/`color`/`icon`/`iconSvg`/`slug`）/
`owner`（内联 `name`/`displayName`/`avatar`）/ `publishTime` / `lastEditTime`。

> 正文 `content` 写入时已白名单净化，模板可 `th:utext` 输出。

### 3. 公开 REST API（匿名可读）

前缀 `/apis/api.bbs.timxs.com/v1alpha1`：

| 接口 | 说明 |
| --- | --- |
| `GET /posts?page=&size=&categorySlug=&categoryName=&keyword=` | 已发布分页（置顶优先，size 上限 50） |
| `GET /posts/{slug}` | 详情（含正文） |
| `GET /announcements?limit=` | 已发布公告 |
| `GET /categories` | 启用中的分类 |

完整文档见 Halo `/swagger-ui.html`（`BbsV1alpha1Public` / `BbsV1alpha1Console` / `BbsV1alpha1Uc`）。

## 数据模型

- **BbsPost**（`bbs.timxs.com/v1alpha1`）：标题、slug（唯一）、类型（公告 / 帖子）、分类（可空）、正文（净化 HTML）、摘要、置顶 + 权重、状态（草稿 / 待审核 / 已发布 / 已驳回）、发布时间、最后编辑时间、作者
- **BbsCategory**：名称、slug（唯一）、描述、Iconify（含离线 SVG）、主题色、排序、启用开关

卸载时可在 Console 勾选「同时删除数据」清理自定义模型。

## License

GPL-3.0
