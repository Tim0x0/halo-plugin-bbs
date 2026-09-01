/**
 * Console 路由（管理端）：帖子列表为入口菜单，
 * 分类管理与编辑器不配菜单，由列表页按钮进入（对标官方文章模块）。
 *
 * 数组内混有两种形态：带 {@code parentName} 的（挂进根布局、继承侧边栏）与
 * 独立顶层路由（如快照页，全屏无侧边栏）。官方类型声明只允许纯
 * RouteRecordRaw[] 或纯 RouteRecordAppend[]，但运行时按条分派
 * （{@code "parentName" in route}），混挂是支持的——故此处放宽声明，
 * 入口处以 RouteRecordAppend[] 交付。
 */
import { markRaw } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import type { RouteRecordAppend } from '@halo-dev/ui-shared'
import RiMegaphoneLine from '~icons/ri/megaphone-line'

export const consoleRoutes: (RouteRecordRaw | RouteRecordAppend)[] = [
  {
    parentName: 'Root',
    route: {
      path: '/bbs',
      name: 'BbsPosts',
      component: () => import('@/console/views/PostListView.vue'),
      meta: {
        title: '帖子',
        searchable: true,
        permissions: ['plugin:bbs:view'],
        menu: {
          name: '帖子',
          group: 'content',
          icon: markRaw(RiMegaphoneLine),
          priority: 6,
        },
      },
    },
  },
  {
    parentName: 'Root',
    route: {
      path: '/bbs/categories',
      name: 'BbsCategories',
      component: () => import('@/console/views/CategoryListView.vue'),
      meta: {
        title: '帖子分类',
        permissions: ['plugin:bbs:manage'],
      },
    },
  },
  {
    parentName: 'Root',
    route: {
      path: '/bbs/editor',
      name: 'BbsPostEditor',
      component: () => import('@/console/views/PostEditorView.vue'),
      meta: {
        title: '编辑帖子',
        // 对齐官方文章编辑器：隐藏布局 footer，保证页面正好撑满 100vh、
        // VPageHeader 固定不随正文滚动（编辑器容器高度即 calc(100vh - 3.5rem)）
        hideFooter: true,
        // 版主（plugin:bbs:moderate）可编辑帖子；完整管理角色的 ui-permissions 已包含该项
        permissions: ['plugin:bbs:moderate'],
      },
    },
  },
  // 顶层路由（裸路由对象、不配 parentName，不挂根布局）：对齐官方快照页——
  // 全屏独立页，没有 Console 左侧菜单栏。官方 /posts/snapshots 同样定义在
  // BasicLayout 之外。?name= 指定帖子
  {
    path: '/bbs/snapshots',
    name: 'BbsPostSnapshots',
    component: () => import('@/console/views/PostSnapshotsView.vue'),
    meta: {
      title: '历史版本',
      // 快照读权限随帖子读（bbs-view）；恢复/删除操作由后端按版主管辖兜底
      permissions: ['plugin:bbs:view'],
    },
  },
]
