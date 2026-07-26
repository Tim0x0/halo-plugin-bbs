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

export type PostType = 'ANNOUNCEMENT' | 'POST'
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
  phase: PostPhase
  /** 驳回原因（仅 REJECTED 状态有值） */
  rejectReason?: string
  publishTime?: string
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
  /** Iconify 图标名（如 mdi:bullhorn） */
  icon?: string
  /** 保存时按 icon 解析的内联 SVG（前台离线渲染用） */
  iconSvg?: string
  color?: string
  priority?: number
  enabled?: boolean
}

export interface BbsCategory {
  apiVersion: string
  kind: string
  metadata: Metadata
  spec: BbsCategorySpec
}

/** 后端 CategoryVo（自包含展示属性 + 已发布帖子数） */
export interface CategoryVo {
  name: string
  displayName: string
  slug: string
  description?: string
  icon?: string
  iconSvg?: string
  color?: string
  priority?: number
  enabled?: boolean
  postCount?: number
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

/** RFC 6902 json-patch 操作 */
export interface JsonPatchOp {
  op: 'add' | 'remove' | 'replace' | 'move' | 'copy' | 'test'
  path: string
  value?: unknown
  from?: string
}
