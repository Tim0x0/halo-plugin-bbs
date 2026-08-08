/**
 * BBS 社区插件 API 封装：Console 管理端点、UC 用户端点与
 * Halo 自动生成的分类 CRUD（URL 唯一出处）。
 */
import { axiosInstance } from '@halo-dev/api-client'
import type {
  BbsCategory,
  BbsPost,
  BbsPostVo,
  CategoryVo,
  JsonPatchOp,
  ListResult,
  PostRequest,
} from '@/types/bbs'

const CONSOLE_BASE = '/apis/console.api.bbs.timxs.com/v1alpha1'
const UC_BASE = '/apis/uc.api.bbs.timxs.com/v1alpha1'
const CRUD_BASE = '/apis/bbs.timxs.com/v1alpha1'

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
  }) {
    return axiosInstance.get<ListResult<BbsPostVo>>(`${CONSOLE_BASE}/bbsposts`, { params })
  },
  getPost(name: string) {
    return axiosInstance.get<BbsPostVo>(`${CONSOLE_BASE}/bbsposts/${name}`)
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
    return axiosInstance.put(`${CONSOLE_BASE}/bbsposts/${name}/publish`)
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
  listCategories() {
    return axiosInstance.get<CategoryVo[]>(`${CONSOLE_BASE}/bbscategories`)
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
  create(body: PostRequest) {
    return axiosInstance.post<BbsPost>(`${UC_BASE}/bbsposts`, body)
  },
  update(name: string, body: PostRequest) {
    return axiosInstance.put<BbsPost>(`${UC_BASE}/bbsposts/${name}`, body)
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
}

// ---------------- 帖子 spec 快捷 patch（设置弹窗用，不触碰正文） ----------------

export const postCrudApi = {
  patch(name: string, ops: JsonPatchOp[]) {
    return axiosInstance.patch(`${CRUD_BASE}/bbsposts/${name}`, ops, {
      headers: { 'Content-Type': 'application/json-patch+json' },
    })
  },
}
