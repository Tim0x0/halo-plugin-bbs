/**
 * BBS 社区插件共享类型（后端 Extension / VO / 请求体的 TS 映射）。
 * 集中放在 @/types，避免 vue-tsc 对组件文件内导出类型的推断问题。
 */

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

export interface BbsPostSpec {
  title: string
  slug: string
  type: PostType
  categoryName?: string
  excerpt?: string
  content?: string
  owner?: string
  pinned?: boolean
  pinPriority?: number
  /** 是否允许评论（false 时前台不渲染评论区） */
  allowComment?: boolean
  /** 是否锁定（禁评论、禁作者编辑与删除） */
  locked?: boolean
  /** 问答帖是否已解决（仅 QUESTION 有意义） */
  solved?: boolean
  phase: PostPhase
  /** 驳回原因（仅 REJECTED 状态有值） */
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
}

export interface BbsCategorySpec {
  displayName: string
  slug: string
  description?: string
  /** Iconify 图标名（如 mdi:bullhorn）；可空 */
  icon?: string
  /** 保存时按 icon 解析的内联 SVG（currentColor，前台用 color 着色） */
  iconSvg?: string
  /** 分类色 HEX（来自 Iconify 选色）；无图标时可空 */
  color?: string
  /** 父分类的 metadata.name；空=一级分类（仅允许两级） */
  parentName?: string
  /** 封面图 URL（分类页 hero）；子分类留空=继承父分类 */
  cover?: string
  priority?: number
  enabled?: boolean
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
  iconSvg?: string
  color?: string
  /** 父分类的 metadata.name；空=一级分类 */
  parentName?: string
  /** 封面图 URL（子分类已继承父分类封面） */
  cover?: string
  /** 父分类摘要（子分类才有） */
  parent?: CategoryVo
  /** 子分类列表（仅一级分类填充） */
  children?: CategoryVo[]
  priority?: number
  enabled?: boolean
  /** 本分类直属的已发布帖子数 */
  postCount?: number
  /** 含子分类的已发布帖子总数 */
  totalPostCount?: number
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
  pinned?: boolean
  pinPriority?: number
  /** 是否允许评论 */
  allowComment?: boolean
  /** 是否已锁定 */
  locked?: boolean
  /** 问答帖是否已解决 */
  solved?: boolean
  /** 驳回原因（仅 REJECTED 状态有值） */
  rejectReason?: string
  /** 公开可见的评论数（Halo 评论体系） */
  commentCount?: number
  excerpt?: string
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
  excerpt?: string
  content?: string
  /** 是否允许评论（null 表示不修改） */
  allowComment?: boolean
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
  allowComment: boolean
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
    allowComment: true,
    pinned: false,
    pinPriority: 0,
  }
}

/** RFC 6902 json-patch 操作 */
export interface JsonPatchOp {
  op: 'add' | 'remove' | 'replace' | 'move' | 'copy' | 'test'
  path: string
  value?: unknown
  from?: string
}
