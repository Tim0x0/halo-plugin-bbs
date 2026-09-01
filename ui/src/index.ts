import { definePlugin } from '@halo-dev/ui-shared'
import type { CommentSubjectRefProvider, RouteRecordAppend } from '@halo-dev/ui-shared'
import type { Extension } from '@halo-dev/api-client'
import { consoleRoutes } from '@/console/routes'
import { ucRoutes } from '@/uc/routes'
import type { BbsPost } from '@/types/bbs'
import '@/styles/tokens.css'

export default definePlugin({
  components: {},
  // 两种路由形态混挂（根布局子路由 + 独立顶层路由），运行时按条分派；
  // 官方类型只允许纯数组二选一，见 routes.ts 头部说明
  routes: consoleRoutes as RouteRecordAppend[],
  ucRoutes: ucRoutes as RouteRecordAppend[],
  extensionPoints: {
    // 让 Halo 评论管理后台识别 BBS 帖子评论的来源（标签 + 标题 + 跳转）。
    // 评论列表（CommentListItem）走前端 useSubjectRef，内置只认 Post/SinglePage
    // （content.halo.run）；不注册则 bbs.timxs.com/BbsPost 命中 unknown，
    // 显示「未知」且无法跳转。后端 CommentSubject 仅用于评论通知，与此无关。
    'comment:subject-ref:create': (): CommentSubjectRefProvider[] => [
      {
        kind: 'BbsPost',
        group: 'bbs.timxs.com',
        resolve: (subject: Extension) => {
          const post = subject as unknown as BbsPost
          return {
            label: 'BBS 帖子',
            title: post.spec?.title ?? '帖子',
            // 前台帖子页（新窗口打开）
            externalUrl: post.spec?.slug ? `/bbs/post/${post.spec.slug}` : undefined,
            // 跳本插件 console 帖子编辑器
            route: {
              name: 'BbsPostEditor',
              query: { name: post.metadata?.name ?? '' },
            },
          }
        },
      },
    ],
  },
})
