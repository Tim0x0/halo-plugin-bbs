import { onBeforeUnmount } from 'vue'
import { stores } from '@halo-dev/ui-shared'

/**
 * 会话保活，对齐官方 `use-session-keep-alive`：长文写作期间定时探活，
 * 避免 session 过期后静默保存全部 401 丢稿。
 *
 * 官方用 vue-query 的 refetchIntervalInBackground；插件未依赖 vue-query，
 * 这里用 setInterval 等价实现（后台标签页会被浏览器节流到 ≥1 次/分钟，
 * 5 分钟的间隔不受影响），另补窗口聚焦时立即探活一次。
 */
const KEEP_ALIVE_INTERVAL = 1000 * 60 * 5

function ping(isAnonymous: () => boolean) {
  if (isAnonymous()) return
  // 离线 / 网络抖动时 fetch 会 reject，吞掉避免 unhandled rejection
  fetch('/actuator/health').catch(() => undefined)
}

export function useSessionKeepAlive() {
  const currentUserStore = stores.currentUser()
  const isAnonymous = () => currentUserStore.isAnonymous
  const keepAlive = () => ping(isAnonymous)

  keepAlive()
  const timer = window.setInterval(keepAlive, KEEP_ALIVE_INTERVAL)

  window.addEventListener('focus', keepAlive)
  onBeforeUnmount(() => {
    window.clearInterval(timer)
    window.removeEventListener('focus', keepAlive)
  })
}
