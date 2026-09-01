/**
 * BBS 社区插件共享类型（后端 Extension / VO / 请求体的 TS 映射）。
 * 集中放在 @/types，避免 vue-tsc 对组件文件内导出类型的推断问题。
 */
import type { Component } from 'vue'

export interface Metadata {
  name: string
  generateName?: string
  labels?: Record<string, string>
  annotations?: Record<string, string>
  version?: number
  creationTimestamp?: string
  deletionTimestamp?: string
}

export interface ListResult<T> {
  page: number
  size: number
  total: number
  items: T[]
}

export type PostType = 'ANNOUNCEMENT' | 'POST' | 'QUESTION'
export type PostPhase = 'DRAFT' | 'PENDING' | 'PUBLISHED' | 'REJECTED'

/** 摘要（对齐官方 Post.spec.excerpt 结构） */
export interface BbsPostExcerpt {
  autoGenerate?: boolean
  /** 手工摘要原文（autoGenerate=false 时生效） */
  raw?: string
}

/** 已发布帖子的工作稿元数据；正文由 BbsPost.spec.headSnapshot 指向。 */
export interface BbsPostDraft {
  title: string
  slug: string
  type: PostType
  categoryName?: string
  excerpt?: BbsPostExcerpt
  /** 修改稿流程状态；不会使用 PUBLISHED */
  phase?: Exclude<PostPhase, 'PUBLISHED'>
  rejectReason?: string
  lastEditTime?: string
}

export interface BbsPostSpec {
  title: string
  slug: string
  type: PostType
  categoryName?: string
  excerpt?: BbsPostExcerpt
  /** Halo 核心 Snapshot 指针。 */
  baseSnapshot?: string
  headSnapshot?: string
  releaseSnapshot?: string
  /** 初始化中断暂存：首个 Snapshot 建立前的兜底窗口期字段，正常恒为 null，调和器消费后清空 */
  content?: string
  /** 仅已发布帖子存在；只保存工作稿元数据。 */
  draft?: BbsPostDraft
  owner?: string
  pinned?: boolean
  pinPriority?: number
  /** 是否锁定（禁评论、禁作者编辑与删除） */
  locked?: boolean
  /** 问答帖是否已解决（仅 QUESTION 有意义） */
  solved?: boolean
  phase: PostPhase
  /** 帖子或已发布修改稿被驳回时的原因 */
  rejectReason?: string
  publishTime?: string
  /** 最后活跃时间（发布或收到公开评论时更新） */
  lastActivityTime?: string
  lastEditTime?: string
}

export interface BbsPost {
  apiVersion: string
  kind: string
  metadata: Metadata
  spec: BbsPostSpec
  status?: {
    commentsCount?: number
    /** 官方口径评论总数（不区分审核与隐藏） */
    totalCommentCount?: number
    /** 待审核评论数 */
    pendingCommentCount?: number
    headSnapshotVersion?: number
  }
}

/**
 * 快照列表项，对齐 Halo 官方 ListedSnapshotDto。
 *
 * 只带快照自身的 metadata 与创建者/修改时间——标题、分类等业务字段不入版本链。
 * 「基础 / 工作中 / 已发布」徽标由帖子的三指针与 metadata.name 比对得出。
 */
export interface BbsSnapshotDto {
  metadata: Metadata
  spec: {
    owner?: string
    modifyTime?: string
  }
}

/** 正文内容响应（对齐官方 Content，另带快照名与乐观锁版本供并发检测）。 */
export interface BbsContentVo {
  raw: string
  content: string
  rawType: string
  snapshotName?: string
  version?: number
}

/** 只保存正文的请求体，对齐官方 ContentUpdateParam。 */
export interface ContentUpdateParam {
  /** 载入时的 head 快照版本；与服务端不一致时服务端分叉新快照而不是覆盖 */
  version?: number
  raw?: string
  content?: string
  rawType?: string
}

export type ModerationAction =
  | 'SUBMITTED'
  | 'PUBLISHED'
  | 'APPROVED'
  | 'REJECTED'
  | 'SUBMISSION_WITHDRAWN'
  | 'UNPUBLISHED'

export interface BbsModerationRecord {
  apiVersion: string
  kind: 'BbsModerationRecord'
  metadata: Metadata
  spec: {
    postName: string
    action: ModerationAction
    actor: string
    snapshotName?: string
    fromPhase?: string
    toPhase?: string
    reason?: string
    createdAt: string
  }
}

/** 评论管理 owner（Email kind 的 name 是邮箱，后端不下发） */
export interface BbsCommentAdminOwner {
  /** User kind=username；Email kind=null */
  name?: string
  displayName?: string
  avatar?: string
  /** User / Email */
  kind?: string
}

/** Console 评论管理列表项（帖子列表评论列弹窗） */
export interface BbsCommentAdminVo {
  name: string
  owner?: BbsCommentAdminOwner
  content?: string
  approved?: boolean
  hidden?: boolean
  top?: boolean
  priority?: number
  creationTime?: string
  approvedTime?: string
  /** 回复总数（含未审核与隐藏） */
  replyCount?: number
  deleting?: boolean
  ipAddress?: string
  userAgent?: string
}

/** Console 回复管理列表项 */
export interface BbsReplyAdminVo {
  name: string
  owner?: BbsCommentAdminOwner
  content?: string
  approved?: boolean
  hidden?: boolean
  creationTime?: string
  approvedTime?: string
  deleting?: boolean
  commentName?: string
  quoteReply?: string
}

/** 编辑器详情面板的键值条目；value 缺省渲染为「—」。
 *  形态对齐官方编辑器详情页签：灰底卡片（标签 + 角标图标 + 值），
 *  短条目（字符数 / 词数等）两两同行，长条目独占整行 */
export interface PostDetailItem {
  label: string
  value?: string
  /** 提供时 value 渲染为新标签页外链（访问链接用） */
  href?: string
  /** 卡片右上角角标图标（@halo-dev/components 的图标组件） */
  icon?: Component
  /** 半宽条目：与相邻 half 条目并排一行两列（官方字符数 + 词数的排法） */
  half?: boolean
}

export interface BbsCategorySpec {
  displayName: string
  slug: string
  description?: string
  /** Iconify 图标名（如 mdi:bullhorn）；可空 */
  icon?: string
  /** Iconify 选择器输出的内联 SVG：选色已烤进 fill，未选色为 currentColor 随文字色 */
  iconSvg?: string
  /** 分类色 HEX（含透明，独立于图标）；空=不上色 */
  color?: string
  /** 父分类的 metadata.name；空=一级分类（仅允许两级） */
  parentName?: string
  /** 封面图 URL（分类页 hero）；子分类留空=继承父分类 */
  cover?: string
  priority?: number
  enabled?: boolean
  /** 此分类树（本级 + 子分类）下置顶帖是否出现在首页顶部；仅一级分类可设 */
  pinToHome?: boolean
  /**
   * 此分类树（本级 + 子分类）的版主角色（Halo 角色 metadata.name）；仅一级分类可设。
   * 留空=无分区版主，仅全站版主与管理角色可管
   */
  moderatorRoles?: string[]
}

export interface BbsCategory {
  apiVersion: string
  kind: string
  metadata: Metadata
  spec: BbsCategorySpec
}

/** 后端 CategoryVo（自包含展示属性 + 已发布帖子数；层级信息已解析） */
export interface CategoryVo {
  name: string
  displayName: string
  slug: string
  description?: string
  icon?: string
  /** 内联 SVG：颜色自带（选色烤进 fill，未选色为 currentColor） */
  iconSvg?: string
  color?: string
  /** 父分类的 metadata.name；空=一级分类 */
  parentName?: string
  /** 封面图 URL（子分类已继承父分类封面） */
  cover?: string
  /** 前台访问地址 */
  permalink?: string
  /** 父分类摘要（子分类才有） */
  parent?: CategoryVo
  /** 子分类列表（仅一级分类填充） */
  children?: CategoryVo[]
  priority?: number
  enabled?: boolean
  /** 此分类树（本级 + 子分类）下置顶帖是否出现在首页顶部；仅一级分类有意义 */
  pinToHome?: boolean
  /** 本分类直属的已发布帖子数 */
  postCount?: number
  /** 含子分类的已发布帖子总数 */
  totalPostCount?: number
  /** 创建时间（ISO；Console 分类列表绝对时间） */
  creationTimestamp?: string
}

export interface OwnerVo {
  name: string
  displayName: string
  avatar?: string
}

/** 后端 BbsPostVo（列表 content 为 null，详情才有） */
export interface BbsPostVo {
  name: string
  title: string
  slug: string
  type: PostType
  phase: PostPhase
  /** 已发布修改稿的状态；仅 Console / UC 编辑视图返回 */
  draftPhase?: Exclude<PostPhase, 'PUBLISHED'>
  /** 是否存在独立于前台发布版本的工作稿 */
  hasDraft?: boolean
  /** 快照三指针与 head 乐观锁版本：历史面板据此打徽标，保存时回传 version 检测并发 */
  baseSnapshot?: string
  headSnapshot?: string
  releaseSnapshot?: string
  snapshotVersion?: number
  pinned?: boolean
  /** 本次列表视图是否真的浮顶（徽标以此为准，而非 pinned） */
  pinnedInView?: boolean
  pinPriority?: number
  /** 是否已锁定 */
  locked?: boolean
  /** 问答帖是否已解决 */
  solved?: boolean
  /** 当前未发布稿或已发布修改稿的驳回原因 */
  rejectReason?: string
  /** 公开可见的评论数（Halo 评论体系） */
  commentsCount?: number
  /** 官方口径评论总数（不区分审核与隐藏；不含回复） */
  totalCommentCount?: number
  /** 待审核评论数；大于 0 时 Console 列表评论列上色并可点击 */
  pendingCommentCount?: number
  /** 展示摘要（后端已折算：自动模式为实时截取的正文，手工模式为原文） */
  excerpt?: string
  /** 摘要是否自动生成——表单开关回填用 */
  autoExcerpt?: boolean
  content?: string
  permalink?: string
  category?: CategoryVo
  owner?: OwnerVo
  publishTime?: string
  /** 最后活跃时间 */
  lastActivityTime?: string
  lastEditTime?: string
  creationTimestamp?: string
}

/** 创建 / 更新帖子请求体（与后端 PostRequest 对应） */
export interface PostRequest {
  title: string
  slug?: string
  type?: PostType
  categoryName?: string
  /** 手工摘要原文（autoExcerpt=false 时生效） */
  excerpt?: string
  /** 摘要是否自动截取；不传表示不修改（新建默认自动） */
  autoExcerpt?: boolean
  content?: string
  pinned?: boolean
  pinPriority?: number
}

/** 编辑器 / 设置表单的本地状态 */
export interface PostFormState {
  title: string
  slug: string
  type: PostType
  categoryName: string
  autoExcerpt: boolean
  excerpt: string
  content: string
  pinned: boolean
  pinPriority: number
}

export function defaultPostForm(): PostFormState {
  return {
    title: '',
    slug: '',
    type: 'POST',
    categoryName: '',
    autoExcerpt: true,
    excerpt: '',
    content: '',
    pinned: false,
    pinPriority: 0,
  }
}

/**
 * 帖子 VO → 表单状态回填（列表设置弹窗与两侧编辑器的共用口径）。
 * managed=false（UC 侧）无置顶特权字段，恒为 false / 0。
 */
export function postFormFrom(post: BbsPostVo, opts?: { managed?: boolean }): PostFormState {
  const managed = opts?.managed ?? true
  return {
    title: post.title || '',
    slug: post.slug || '',
    type: post.type || 'POST',
    categoryName: post.category?.name || '',
    autoExcerpt: !!post.autoExcerpt,
    excerpt: post.excerpt || '',
    content: post.content || '',
    pinned: managed && !!post.pinned,
    pinPriority: managed ? post.pinPriority || 0 : 0,
  }
}

/** RFC 6902 json-patch 操作 */
export interface JsonPatchOp {
  op: 'add' | 'remove' | 'replace' | 'move' | 'copy' | 'test'
  path: string
  value?: unknown
  from?: string
}
