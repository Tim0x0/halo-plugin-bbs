# BBS 社区（plugin-bbs）

Halo 2.x 插件：**讨论 + 问答 + 公告 + 两级分类**，适合博客站旁的隔离社区。

## 功能特性

- **帖子类型**：讨论（POST）、问答（QUESTION）、公告（ANNOUNCEMENT，仅管理端可发）。三类帖必须归属分类，与置顶正交
- **问答**：作者与版主可标记「已解决」；改出问答类型时服务端清掉已解决残留
- **锁定**：版主操作——禁评论、禁作者编辑 / 删除。锁定帖不渲染评论组件，历史评论走只读接口（只看不赞）
- **置顶**：独立开关 + 权重。分类页第 1 页浮顶；所属一级分类开启「置顶帖上首页」时同时出现在首页第 1 页顶部。徽标看 `pinnedInView`（本视图是否真浮顶），不是 `pinned`
- **分类**：两级。Iconify 图标（保存时存下选择器输出的离线 SVG，选色已烤进 `fill`，未选色随文字色）+ 独立分类色（新建按名称预填实色；清空不上色）+ slug + 封面。板块级配置（`pinToHome`、`moderatorRoles`）仅一级可设，调和器抹掉子分类上的值
- **分区版主**：一级分类可指定角色；持有该角色者管辖本分类树。全站版主（直接绑定 `bbs-moderate` / `bbs-manage` / 超管）不受限。管辖判定**不展开**角色依赖链
- **状态**：草稿 / 待审核 / 已发布 / 已驳回；软删除进回收站，彻底删除仅管理角色
- **审核（可选）**：用户发帖须审核；可配置「编辑已发布是否重新审核」。驳回后重提永远重审。审核中保存只更新内容、不改审核状态（WordPress 式）；作者可显式「取消提交」退回草稿；已驳回的帖子既可由作者修改重提，也可由版主直接通过
- **评论**：接入 Halo 官方评论体系。详情页由「评论组件」插件渲染；锁定帖改只读渲染
- **全文搜索**：接入 Halo 搜索，已发布帖子可被站点全局搜索
- **RSS**：经 plugin-feed 输出全站 `/feed/bbs/posts.xml` 与一级分类 `/feed/bbs/categories/{slug}.xml`（未安装则无 RSS，其余照常）
- **前台**：Flarum 两栏——白顶栏 + 品牌色 Hero、左栏分类树、紧凑列表。圆标（公告 / 置顶 / 未解决问答）+ 线框（已解决 / 锁）。默认按最后活跃排序
- **作者入口**：无独立 `/bbs/u` 作者页。作者名按「作者链接模板」跳转（默认 `/authors/{name}`）；可选接入 **interaction-plus**（装扮 + 用户卡链接优先）
- **面向主题**：Finder（`${bbs}`）+ 公开 REST API；主题可覆盖 `bbs.html` / `bbs_post.html`
- **安全**：正文 HTML 服务端白名单净化；SVG 图标零 URL / 零事件属性；锁帖写入在安全链前拦截

## 环境要求

- Halo `>= 2.25.0`
- 构建：JDK 21、Node 18+、pnpm
- 可选：`interaction-plus` `>= 1.0.0`（装扮与用户卡链接；未安装时 BBS 照常运行）
- 可选：`PluginFeed` `>= 1.4.0`（RSS；未安装时无订阅源，其余照常）

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

## 发布自动发帖（可选）

> 面向在 GitHub 上托管本项目、以 GitHub Releases 发版的维护者；不在此场景可忽略本节。

仓库自带工作流 `.github/workflows/bbs-post.yaml`：发布 release 时，自动把发布说明发到**你自己的** BBS 社区（调 Console API 建帖，正文由 release notes 转 HTML），版本公告不用再手工发一遍。未配置时它不会做任何事；确定不需要可直接删除该文件，不影响插件本身。

### 配置

仓库 **Settings → Secrets and variables → Actions**：

| 配置项 | 类型 | 说明 |
| --- | --- | --- |
| `BBS_POST_PAT` | Secret | **本插件所在站点**的个人访问令牌，持牌账号需有「BBS 社区版主」（`bbs-moderate`）及以上角色。与官方 CD 上传应用市场用的 `halo-pat`（halo.run 官方站凭据）是两回事，勿混用 |
| `HALO_BASE_URL` | Variable | 站点地址（如 `https://example.com`），须可被 GitHub 公网访问 |
| `BBS_CATEGORY_NAME` | Variable | 目标分类的 `metadata.name`（形如 `category-xxxxxxxx`） |
| `BBS_PROJECT_NAME` | Variable（可选） | 标题前缀，按原样拼接（格式自己写，如 `[BBS 社区]`、`BBS 社区 |`）。有值则标题为 `{前缀} {tag} 发布说明`；不配则保持 `{tag} 发布说明`。手动运行时 `title` 输入优先 |

`metadata.name` 是分类的资源主键，**既不是中文名也不是 slug**——`spec.displayName` 才是中文名，`spec.slug` 是前台链接别名。查询方式：

```bash
curl -s "https://你的站点/apis/bbs.timxs.com/v1alpha1/bbscategories" \
  -H "Authorization: Bearer <令牌>" \
  | jq '.items[] | {name: .metadata.name, 名称: .spec.displayName, slug: .spec.slug}'
```

发帖行为：

- **状态**：正式 release → 直接发布；预发布（prerelease）→ 存草稿；手动运行（Actions → Release 发帖 → Run workflow）可覆盖
- **不重复发帖**：帖子别名固定为 `release-<tag>`（如 `v1.2.3` → `release-v123`）。删了 release 重发、手动重跑都只更新已有帖；回收站内的帖视为不存在，会新建
- **内容**：标题默认 `<tag> 发布说明`（`tag` 来自 git tag，不是 GitHub Release 标题）；配了 `BBS_PROJECT_NAME` 则按原样作为前缀拼成 `{前缀} {tag} 发布说明`。正文前后自动拼 release 页面链接与附件下载列表（会等待 CD 上传完 jar，超时则降级为不带下载链接）

## 使用说明

### Console（后台）

「BBS 社区」菜单（内容分组）：

- **帖子列表**：按状态 / 类型 / 分类 / 作者 / 关键词筛选；行内编辑、设置、置顶、锁定、回收，以及发布 / 通过 / 驳回 / 取消提交（纯草稿走「发布」，进过审核的走「通过」；已驳回帖可由作者修改重提，也可版主直接通过；待审核帖可撤回提交；设置弹窗可保存并直接「发布 / 通过」）。分区版主只看见自己管辖的分类
- **写帖子 / 公告**：全屏富文本编辑器；设置里选类型、分类（必选）、置顶与权重、别名、摘要
- **分类管理**：由帖子列表页「分类」进入。两级树、拖拽排序、封面、板块版主角色

### 用户中心（UC）

「我的帖子」：登录用户发讨论 / 问答、编辑、删除自己的帖子（**发帖必须登录**）。用户不能发公告、不能置顶、不能锁定。编辑器首次手动保存、自动保存或 `Ctrl/Cmd+S` 会创建服务端草稿与 Halo 核心 Snapshot；编辑已发布帖子时，静默保存写入独立 `headSnapshot`，前台仍读取 `releaseSnapshot`。只有显式提交才进入审核或发布；已发布修改需审核时，旧发布版本在审核期间继续公开。提交入口三处：编辑器顶部、列表行菜单与设置弹窗（草稿 / 已驳回直接「提交」；已发布帖有未提交修改时「提交修改」；缺分类先弹设置补齐后可就地提交）。待审核期间保存只更新内容、不退出审核队列；想撤回须显式「取消提交」（列表行菜单），退回草稿后可继续编辑重提。编辑器「历史」可查看完整版本、并排对照、恢复、删除及对应审核时间线。默认提交即发布；开启「用户发帖需审核」后进入待审核。锁定帖作者不可再编辑或删除。

### 前台

| 路径 | 说明 |
| --- | --- |
| `/bbs` | 列表。查询参数：`category`（分类 slug）、`q`（标题关键词）、`page`、`sort`（`active` \| `latest` \| `hot`，默认 `active`）、`type`（`post` \| `question` \| `announcement`） |
| `/bbs/post/{slug}` | 详情：楼主流 + 评论区 + 相关推荐；桌面右栏目录（正文 h2/h3 ≥ 3） |
| `/feed/bbs/posts.xml` | 全站 RSS 2.0（需 plugin-feed） |
| `/feed/bbs/categories/{slug}.xml` | 一级分类树 RSS（需 plugin-feed） |

顶栏可选挂站点菜单：插件设置「外观 → 品牌 → 顶栏菜单」选已有菜单组，留空则顶栏不显示导航。多级菜单一律点击展开（不做悬停展开）：桌面点箭头出下拉（子项缩进平铺），窄屏进汉堡、点箭头逐层展开；带链接的父项文字跳转、箭头开合，不带链接的父项整项即开关。兼容 2.25 旧存法（`menuItems` + `children`）与 2.26 新存法（`menuName` + `parent`）及两者混存。

**作者名链接**（无独立 BBS 作者页）：

1. 插件设置「作者链接模板」（默认 `/authors/{name}`，`{name}` = 用户名；**留空 = 作者名不跳转**）
2. 若开启「接入互动增强」，且 interaction-plus 的「用户卡跳转链接」非空 → **优先用对方的模板**
3. 互动增强未安装 / 未就绪 / 模板为空 → **静默回退** BBS 模板，不报错

主题作者页若要展示该用户的 BBS 帖子，使用 Finder：`listPostsByOwner` / `getAuthor`。

### 评论

- 前台需安装官方 **评论组件**（plugin-comment-widget）；未安装时评论区为空、不影响页面
- 登录 / 审核策略由「系统设置 → 评论」统一控制
- 评论管理复用 Halo 后台「评论」页
- 锁定帖：模板不渲染 `<halo:comment>`，历史评论由前台走 `GET /posts/{name}/comments` 只读渲染。写入被服务端拦截

### 互动增强（可选）

插件设置 → **集成**：

| 项 | 说明 |
| --- | --- |
| **接入互动增强** | 需已安装并启用 interaction-plus。开启后：① 前台装扮（头像框 / 称号 / 勋章 / 悬浮名片）；② 作者名链接优先用其「用户卡跳转链接」。关闭则不加载装扮，作者名仅用下方模板 |
| **列表页用户装扮** | 需已开启上方开关。在 /bbs 列表显示头像框、昵称样式与身份标识；关闭则列表纯净显示且不加载装扮脚本，详情页与评论区装扮不受影响。默认关闭 |
| **作者链接模板** | 兜底跳转，默认 `/authors/{name}`；留空表示作者名不可点 |

列表页昵称不渲染完整身份行（密度），仅昵称样式 + 最高优先级身份标识；详情楼主仍可用完整身份行。发帖数可通过扩展点贡献到 interaction-plus 名片统计（插件在 classpath 时自动注册）。

### 插件设置

| 分组 | 内容 |
| --- | --- |
| **外观** | 品牌（标题 / Logo / 顶栏菜单 / 副标题 / 标题分隔符 / 主题色）、Hero（显示开关 / 纯色 / Banner）、页脚声明 |
| **浏览** | 每页条数、列表摘要、时间格式（默认相对）、相关推荐、目录、RSS 条目数 |
| **内容** | 标题最大长度、用户发帖需审核、编辑已发布是否重新审核 |
| **集成** | 接入互动增强、列表页用户装扮、作者链接模板 |

审核通过 / 驳回走 Halo 官方通知中心：作者在 UC 通知列表收到站内消息（可按偏好开邮件）。作者审核自己的帖子不发。事件类型「我的帖子通过审核 / 未通过审核」会出现在用户通知偏好里。

页脚：声明文案（可清空隐藏）+ `© {年} {站点名}`（站点名来自 Halo 系统设置，链到博客首页）+ Powered by bbs（GitHub）。

## 权限模型（角色模板）

| 角色模板 | 说明 | 默认授予 |
| --- | --- | --- |
| BBS 社区后台查看 (`bbs-view`) | Console 只读（列表已按管辖过滤）。**不是**前台浏览权 | 需手动分配 |
| BBS 社区版主 (`bbs-moderate`) | 帖子审核（通过 / 驳回 / 撤回提交）/ 锁定 / 置顶 / 已解决 / 回收；不含彻底删除、分类管理、插件设置 | 需手动分配；也可只作分区版主角色的依赖 |
| BBS 社区管理 (`bbs-manage`) | 版主能力 + 分类 + 彻底删除 + 全部管理接口 | 需手动分配（超管天然拥有） |
| BBS 社区发帖 (`bbs-uc-post`) | 用户中心管理自己的帖子 | 聚合到所有登录用户 |
| 公开读 (`bbs-public-read`) | 前台读取已发布内容与只读评论 | 聚合到匿名 + 登录用户（隐藏） |

前台浏览由 `bbs-public-read` 自动聚合，不必把 `bbs-view` 授给普通用户——否则对方能进管理后台看到草稿 / 待审核 / 回收站。

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

### 3. 公开 REST API（匿名可读）

前缀 `/apis/api.bbs.timxs.com/v1alpha1`：

| 接口 | 说明 |
| --- | --- |
| `GET /posts?page=&size=&categorySlug=&categoryName=&keyword=&sort=&type=` | 已发布分页（`sort`=`active`\|`latest`\|`hot`，默认最后活跃；size 上限 50） |
| `GET /posts/{slug}` | 详情（含正文） |
| `GET /announcements?limit=` | 已发布公告 |
| `GET /categories` | 启用中的分类 |
| `GET /posts/{name}/comments?page=&size=` | 锁定帖的只读评论（`owner.name` 仅 User kind 返回） |
| `GET /comments/{name}/replies?page=&size=` | 某评论的只读楼中楼 |

完整文档见 Halo `/swagger-ui.html`（`BbsV1alpha1Public` / `BbsV1alpha1Console` / `BbsV1alpha1Uc`）。

## 数据模型

- **BbsPost**（`bbs.timxs.com/v1alpha1`）：标题、slug、类型（公告 / 讨论 / 问答）、分类、摘要与业务状态；正文完整复用 Halo 核心 **Snapshot** GVK，通过 `baseSnapshot` / `headSnapshot` / `releaseSnapshot` 区分差异基线、工作版本和前台版本。`spec.draft` 只保存已发布内容的工作稿元数据与审核状态，不再复制正文；此外还包含置顶 + 权重、锁定、已解决、软删除、发布时间、最后活跃时间、最后编辑时间、作者
- **BbsModerationRecord**（`bbs.timxs.com/v1alpha1`）：只追加的提交、发布、通过、驳回、撤稿与取消发布审计事件；历史恢复经提交 / 发布事件附原因记录；每条记录绑定当时的 Snapshot（仅展示参考），历史版本删除遵循官方规则（仅基线与发布中版本不可删），不因审核记录引用而额外保护
- **BbsCategory**：名称、slug（唯一）、描述、Iconify（含离线 SVG，颜色烤在 SVG 里）、分类色、父分类、封面、排序、启用、置顶帖上首页（仅一级）、版主角色（仅一级）

卸载时可在 Console 勾选「同时删除数据」清理自定义模型。

## License

GPL-3.0
