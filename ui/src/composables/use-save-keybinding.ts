import { useDebounceFn, useEventListener } from '@vueuse/core'
import { nextTick } from 'vue'

/**
 * Ctrl+S / ⌘S 保存快捷键，逐条对齐官方 `console-src/composables/use-save-keybinding.ts`：
 * Mac 走 ⌘S、其余走 Ctrl+S，300ms 防抖（按住不放只触发一次），preventDefault 拦掉浏览器另存为。
 */
const IS_MAC =
  typeof navigator !== 'undefined' && /mac|iphone|ipad/i.test(navigator.userAgent)

export function useSaveKeybinding(fn: () => void) {
  const debouncedFn = useDebounceFn(() => {
    fn()
  }, 300)

  useEventListener(window, 'keydown', (e: KeyboardEvent) => {
    if (IS_MAC ? e.metaKey : e.ctrlKey) {
      if (e.key === 's') {
        e.preventDefault()
        nextTick(() => {
          debouncedFn()
        })
      }
    }
  })
}
