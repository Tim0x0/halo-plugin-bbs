/**
 * BBS 社区插件 API 封装：Console 管理端点、UC 用户端点与
 * Halo 自动生成的分类 CRUD（URL 唯一出处）。
 */
import { axiosInstance } from '@halo-dev/api-client'
import type {
  BbsCategory,
  BbsCommentAdminVo,
  BbsContentVo,
  BbsModerationRecord,
  BbsPost,
  BbsPostVo,
  BbsReplyAdminVo,
  BbsSnapshotDto,
  CategoryVo,
  ContentUpdateParam,
  JsonPatchOp,
  ListResult,
  PostRequest,
} from '@/types/bbs'

const CONSOLE_BASE = '/apis/console.api.bbs.timxs.com/v1alpha1'
const UC_BASE = '/apis/uc.api.bbs.timxs.com/v1alpha1'
const CRUD_BASE = '/apis/bbs.timxs.com/v1alpha1'
const PUBLIC_BASE = '/apis/api.bbs.timxs.com/v1alpha1'

/** Console / UC 两侧同形的快照与正文端点，按端点基址生成，避免双份维护。 */
function postContentApi(base: string) {
  return {
    listSnapshots(name: string) {
      return axiosInstance.get<BbsSnapshotDto[]>(`${base}/bbsposts/${name}/snapshot`)
    },
    getContent(name: string, snapshotName?: string) {
      return axiosInstance.get<BbsContentVo>(`${base}/bbsposts/${name}/content`, {
        params: snapshotName ? { snapshotName } : undefined,
      })
    },
    /** 只保存正文；version 与服务端 head 不一致时服务端分叉新快照而不是覆盖。 */
    saveContent(name: string, body: ContentUpdateParam) {
      return axiosInstance.put<BbsPost>(`${base}/bbsposts/${name}/content`, body)
    },
    revertContent(name: string, snapshotName: string) {
      return axiosInstance.put<BbsPost>(`${base}/bbsposts/${name}/revert-content`, {
        snapshotName,
      })
    },
    deleteContent(name: string, snapshotName: string) {
      return axiosInstance.delete<BbsPost>(`${base}/bbsposts/${name}/content`, {
        params: { snapshotName },
      })
    },
    listModerationRecords(name: string) {
      return axiosInstance.get<BbsModerationRecord[]>(
        `${base}/bbsposts/${name}/moderation-records`
      )
    },
  }
}

// ---------------- Console 管理端 ----------------

export const consoleApi = {
  listPosts(params: {
    page?: number
    size?: number
    keyword?: string
    categoryName?: string
    type?: string
    phase?: string
    sort?: string
    owner?: string
    /** true 只看回收站；缺省只看未删除的 */
    deleted?: boolean
  }) {
    return axiosInstance.get<ListResult<BbsPostVo>>(`${CONSOLE_BASE}/bbsposts`, { params })
  },
  getPost(name: string) {
    return axiosInstance.get<BbsPostVo>(`${CONSOLE_BASE}/bbsposts/${name}`)
  },
  ...postContentApi(CONSOLE_BASE),
  // ---- 评论管理（列表评论列弹窗的数据通道） ----
  listPostComments(
    name: string,
    params: {
      page?: number
      size?: number
      /** true=已通过 false=待审核；缺省全部 */
      approved?: string
      keyword?: string
      /** 作者 username */
      owner?: string
      sort?: string
    }
  ) {
    return axiosInstance.get<ListResult<BbsCommentAdminVo>>(
      `${CONSOLE_BASE}/bbsposts/${name}/comments`,
      { params }
    )
  },
  approveComment(name: string, commentName: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/approve`)
  },
  unapproveComment(name: string, commentName: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/unapprove`)
  },
  deleteComment(name: string, commentName: string) {
    return axiosInstance.delete(`${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}`)
  },
  listCommentReplies(
    name: string,
    commentName: string,
    params: { page?: number; size?: number }
  ) {
    return axiosInstance.get<ListResult<BbsReplyAdminVo>>(
      `${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/replies`,
      { params }
    )
  },
  /** 通过该评论下全部未审核回复 */
  approveUnreviewedReplies(name: string, commentName: string) {
    return axiosInstance.put<{ approvedCount: number }>(
      `${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/replies/approve-unreviewed`
    )
  },
  approveReply(name: string, commentName: string, replyName: string) {
    return axiosInstance.put(
      `${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/replies/${replyName}/approve`
    )
  },
  unapproveReply(name: string, commentName: string, replyName: string) {
    return axiosInstance.put(
      `${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/replies/${replyName}/unapprove`
    )
  },
  deleteReply(name: string, commentName: string, replyName: string) {
    return axiosInstance.delete(
      `${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/replies/${replyName}`
    )
  },
  /** 版主以当前用户身份回复（直接通过） */
  createReply(name: string, commentName: string, body: { raw: string; quoteReply?: string }) {
    return axiosInstance.post(
      `${CONSOLE_BASE}/bbsposts/${name}/comments/${commentName}/replies`,
      body
    )
  },
  createPost(body: PostRequest, publish: boolean) {
    return axiosInstance.post<BbsPost>(`${CONSOLE_BASE}/bbsposts`, body, {
      params: { publish },
    })
  },
  updatePost(name: string, body: PostRequest) {
    return axiosInstance.put<BbsPost>(`${CONSOLE_BASE}/bbsposts/${name}`, body)
  },
  publishPost(name: string) {
    return axiosInstance.put<BbsPost>(`${CONSOLE_BASE}/bbsposts/${name}/publish`)
  },
  unpublishPost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/unpublish`)
  },
  approvePost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/approve`)
  },
  rejectPost(name: string, reason?: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/reject`, { reason: reason || '' })
  },
  withdrawPost(name: string) {
    return axiosInstance.put<BbsPost>(`${CONSOLE_BASE}/bbsposts/${name}/withdraw`)
  },
  pinPost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/pin`)
  },
  unpinPost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/unpin`)
  },
  lockPost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/lock`)
  },
  unlockPost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/unlock`)
  },
  solvePost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/solve`)
  },
  unsolvePost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/unsolve`)
  },
  deletePost(name: string) {
    return axiosInstance.delete(`${CONSOLE_BASE}/bbsposts/${name}`)
  },
  /** 从回收站恢复 */
  restorePost(name: string) {
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/restore`)
  },
  /** 彻底删除（不可恢复） */
  deletePostPermanently(name: string) {
    return axiosInstance.delete(`${CONSOLE_BASE}/bbsposts/${name}/permanently`)
  },
  /**
   * 别名占用预检（对齐官方设置弹窗的 slugUniqueValidation）：只查「发布中占用」
   * 口径（已发布 / 待审核 / 已提交修改稿），与后端发布链路一致；
   * 草稿与回收站不占。excludeName 传自己可排除本帖。
   */
  isSlugTaken(slug: string, excludeName?: string) {
    return axiosInstance.get<boolean>(`${CONSOLE_BASE}/bbsposts/slug-taken`, {
      params: { slug, excludeName },
    })
  },
  listCategories() {
    return axiosInstance.get<CategoryVo[]>(`${CONSOLE_BASE}/bbscategories`)
  },
  /**
   * 移动分类位置：只提交「挂到谁下面、排在谁之前」的意图，由服务端重排同级序列。
   * parentName 为 null = 移到根；beforeName 为 null = 追加到同级末尾。
   * 返回重排后的完整分类列表，可直接替换本地树。
   */
  moveCategory(name: string, body: { parentName: string | null; beforeName: string | null }) {
    return axiosInstance.put<CategoryVo[]>(
      `${CONSOLE_BASE}/bbscategories/${name}/position`,
      body
    )
  },
}

// ---------------- UC 用户中心 ----------------

export const ucApi = {
  listMine(params: {
    page?: number
    size?: number
    keyword?: string
    phase?: string
    categoryName?: string
    type?: string
  }) {
    return axiosInstance.get<ListResult<BbsPostVo>>(`${UC_BASE}/bbsposts/mine`, { params })
  },
  getMine(name: string) {
    return axiosInstance.get<BbsPostVo>(`${UC_BASE}/bbsposts/${name}`)
  },
  /** 别名占用预检：同 consoleApi.isSlugTaken，口径一致（发布中占用才算）。 */
  isSlugTaken(slug: string, excludeName?: string) {
    return axiosInstance.get<boolean>(`${UC_BASE}/bbsposts/slug-taken`, {
      params: { slug, excludeName },
    })
  },
  ...postContentApi(UC_BASE),
  /** 首次保存：只创建服务端 DRAFT，不提交审核或发布。 */
  createDraft(body: PostRequest) {
    return axiosInstance.post<BbsPost>(`${UC_BASE}/bbsposts`, body)
  },
  /** 普通保存：写入 headSnapshot；已发布帖不会切换 releaseSnapshot。 */
  saveDraft(name: string, body: PostRequest) {
    return axiosInstance.put<BbsPost>(`${UC_BASE}/bbsposts/${name}`, body)
  },
  /** 显式提交：按审核策略进入 PENDING 或 PUBLISHED。 */
  submit(name: string, body: PostRequest) {
    return axiosInstance.put<BbsPost>(`${UC_BASE}/bbsposts/${name}/submit`, body)
  },
  /** 撤回待审核提交：新帖退回草稿；修改稿退回草稿态，前台发布版不受影响。 */
  withdraw(name: string) {
    return axiosInstance.put<BbsPost>(`${UC_BASE}/bbsposts/${name}/withdraw`)
  },
  solve(name: string) {
    return axiosInstance.put(`${UC_BASE}/bbsposts/${name}/solve`)
  },
  unsolve(name: string) {
    return axiosInstance.put(`${UC_BASE}/bbsposts/${name}/unsolve`)
  },
  delete(name: string) {
    return axiosInstance.delete(`${UC_BASE}/bbsposts/${name}`)
  },
}

// ---------------- 分类 CRUD（Halo 自动生成 API） ----------------

export const categoryApi = {
  create(category: BbsCategory) {
    return axiosInstance.post<BbsCategory>(`${CRUD_BASE}/bbscategories`, category)
  },
  patch(name: string, ops: JsonPatchOp[]) {
    return axiosInstance.patch<BbsCategory>(`${CRUD_BASE}/bbscategories/${name}`, ops, {
      headers: { 'Content-Type': 'application/json-patch+json' },
    })
  },
  delete(name: string) {
    return axiosInstance.delete(`${CRUD_BASE}/bbscategories/${name}`)
  },
  get(name: string) {
    return axiosInstance.get<BbsCategory>(`${CRUD_BASE}/bbscategories/${name}`)
  },
  list(params?: { page?: number; size?: number }) {
    return axiosInstance.get<ListResult<BbsCategory>>(`${CRUD_BASE}/bbscategories`, {
      params: { ...params, sort: 'spec.priority,asc' },
    })
  },
  /**
   * 别名唯一性预检：fieldSelector 精确查询 + size:1 只取 total，
   * 不全量拉取到前端过滤（分类超出单页 size 时会漏判）。
   * spec.slug 有唯一索引、metadata.name 有默认索引，两者均可走索引。
   */
  async isSlugTaken(slug: string, excludeName?: string) {
    const fieldSelector = [`spec.slug=${slug}`]
    if (excludeName) {
      fieldSelector.push(`metadata.name!=${excludeName}`)
    }
    const { data } = await axiosInstance.get<ListResult<BbsCategory>>(
      `${CRUD_BASE}/bbscategories`,
      {
        params: { page: 1, size: 1, fieldSelector },
        // Halo 期望 fieldSelector=a&fieldSelector=b，而非 axios 默认的 fieldSelector[]=a
        paramsSerializer: { indexes: null },
      }
    )
    return (data.total || 0) > 0
  },
}

// ---------------- 公开端点（不需要 Console 权限） ----------------

export const publicApi = {
  /** 启用中的分类（供 UC 等无 Console 权限的入口使用） */
  listCategories() {
    return axiosInstance.get<CategoryVo[]>(`${PUBLIC_BASE}/categories`)
  },
}
