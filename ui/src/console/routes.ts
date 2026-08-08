/**
 * Console 路由（管理端）：BBS 社区列表为入口菜单，
 * 分类管理与编辑器不配菜单，由列表页按钮进入（对标官方文章模块）。
 */
import { markRaw } from 'vue'
import RiMegaphoneLine from '~icons/ri/megaphone-line'

export const consoleRoutes = [
  {
    parentName: 'Root',
    route: {
      path: '/bbs',
      name: 'BbsPosts',
      component: () => import('@/console/views/PostListView.vue'),
      meta: {
        title: 'BBS 社区',
        searchable: true,
        permissions: ['plugin:bbs:view'],
        menu: {
          name: 'BBS 社区',
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
        title: 'BBS 社区分类',
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
        // 版主（plugin:bbs:moderate）可编辑帖子；完整管理角色的 ui-permissions 已包含该项
        permissions: ['plugin:bbs:moderate'],
      },
    },
  },
]
