/**
 * UC 路由（用户中心）：我的帖子 + 发帖编辑器。
 */
import { markRaw } from 'vue'
import RiMegaphoneLine from '~icons/ri/megaphone-line'

export const ucRoutes = [
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
          name: '我的帖子',
          icon: markRaw(RiMegaphoneLine),
          priority: 20,
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
        permissions: ['plugin:bbs:uc'],
      },
    },
  },
]
