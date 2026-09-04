import { useTimeoutFn, useWindowFocus } from '@vueuse/core'
import { onBeforeUnmount, watch, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import type { BbsContentCache } from './use-content-cache'

/**
 * 自动保存，对齐官方 `ui/src/composables/use-auto-save-content.ts`：
 *
 * - 正文变更停止 20 秒后静默保存（每次输入重置计时）
 * - 窗口失焦时保存；关闭页面前尽力保存
 * - 站内路由离开时等本次保存落定再切换（比官方「不等待」多走一步：
 *   我们的列表绿点 / 提交入口吃服务器状态，首屏不能取到保存前状态）；
 *   超时 / 失败仍放行导航，真正的兜底是本地缓存
 * - 只在存在本地缓存条目时触发；允许把正文清空后自动保存
 *
 * 能否真正落库（设置弹窗是否开着、新建是否已满足建稿条件等）
 * 由调用方在 callback 里自行判断——对齐官方把 `settingModal` 判断留在回调内的做法。
 */
export function useAutoSave(
  currentCache: Ref<BbsContentCache | undefined>,
  raw: Ref<string>,
  callback: () => void | Promise<void>
) {
  let disposed = false

  const handleAutoSave = () => {
    if (!disposed && currentCache.value) {
      return callback()
    }
  }

  watch(useWindowFocus(), (focused) => {
    if (!focused) {
      void handleAutoSave()
    }
  })

  // 站内离开：等本次保存落定再切换——列表首屏与「提交修改」入口吃服务器
  // 状态，fire-and-forget 会让首屏取到保存前状态（绿点要刷新才出）。官方
  // 「不等待」的前提是官方没有这类消费方，我们有。超时 / 失败仍放行导航，
  // 本地缓存继续兜底（与不等待等价的最坏情况）。
  onBeforeRouteLeave(() => {
    const p = handleAutoSave()
    if (!p) {
      return true
    }
    return Promise.race([
      Promise.resolve(p).catch(() => undefined),
      new Promise((resolve) => setTimeout(resolve, 1500)),
    ]).then(() => true)
  })

  // beforeunload 里发不保证完成的请求是尽力而为，真正的兜底是本地缓存
  const onBeforeUnload = () => void handleAutoSave()
  window.addEventListener('beforeunload', onBeforeUnload)

  // 必须 immediate:false，避免挂载即起计时。服务端正文异步回填虽仍会触发 raw watch，
  // 但没有编辑器 update 产生的 currentCache，计时结束也不会 PUT。
  const { start, isPending, stop } = useTimeoutFn(
    () => {
      void handleAutoSave()
    },
    20 * 1000,
    { immediate: false }
  )

  function scheduleAutoSave() {
    if (disposed) {
      return
    }
    if (isPending.value) {
      stop()
    }
    start()
  }

  watch(raw, scheduleAutoSave)

  onBeforeUnmount(() => {
    disposed = true
    stop()
    window.removeEventListener('beforeunload', onBeforeUnload)
  })

  // handleAutoSave 仅供内部各触发点共用（路由守卫必须走同一个缓存门槛，
  // 不能直接调用保存回调），不对外暴露。
  return { scheduleAutoSave }
}
