/**
 * UC 路由（用户中心）：我的帖子 + 发帖编辑器。
 *
 * 与 Console 路由同：混有根布局子路由与独立顶层路由（快照页），
 * 官方类型只允许纯数组二选一，运行时支持混挂，类型在此放宽。
 */
import { markRaw } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import type { RouteRecordAppend } from '@halo-dev/ui-shared'
import RiMegaphoneLine from '~icons/ri/megaphone-line'

export const ucRoutes: (RouteRecordRaw | RouteRecordAppend)[] = [
  {
    parentName: 'Root',
    route: {
      path: '/bbs',
      name: 'BbsUcPosts',
      component: () => import('@/uc/views/MyPostsView.vue'),
      meta: {
        title: '我的帖子',
        permissions: ['plugin:bbs:uc'],
        menu: {
          name: '帖子',
          // 官方 UC 侧栏按 group 分区（dashboard/content/interface/...），
          // 「我的帖子」归属「内容」区，对齐官方文章菜单的位置
          group: 'content',
          icon: markRaw(RiMegaphoneLine),
          priority: 20,
          mobile: true,
        },
      },
    },
  },
  {
    parentName: 'Root',
    route: {
      path: '/bbs/editor',
      name: 'BbsUcPostEditor',
      component: () => import('@/uc/views/UcPostEditorView.vue'),
      meta: {
        title: '写帖子',
        // 对齐官方 UC 文章编辑器：隐藏 footer，页面撑满、标题栏固定
        hideFooter: true,
        permissions: ['plugin:bbs:uc'],
      },
    },
  },
  // 顶层路由（裸路由对象、不配 parentName，不挂根布局）：与 Console 快照页
  // 一致的全屏形态。官方 UC 没有历史版本页，此处按官方 Console 快照页的
  // 无侧栏形态对齐。?name= 指定帖子，仅本人帖子
  {
    path: '/bbs/snapshots',
    name: 'BbsUcPostSnapshots',
    component: () => import('@/uc/views/PostSnapshotsView.vue'),
    meta: {
      title: '历史版本',
      permissions: ['plugin:bbs:uc'],
    },
  },
]
