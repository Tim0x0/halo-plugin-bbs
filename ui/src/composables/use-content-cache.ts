import { useLocalStorage, useSessionStorage, useTimeoutFn } from '@vueuse/core'
import { Toast } from '@halo-dev/components'
import { computed, onBeforeUnmount, shallowRef, type Ref } from 'vue'

/**
 * 正文本地缓存（防崩溃 / 误关丢稿），对齐官方 `ui/src/composables/use-content-cache.ts`，
 * 针对 BBS 工作稿模型做了判定改造：
 *
 * 官方用「快照 version 一致才恢复」。BBS 工作稿没有独立 version，改用工作稿
 * lastEditTime（无工作稿时回退发布内容 lastEditTime / 创建时间）
 * 作为内容基线（保存请求期间的后续输入会重定位到新基线），恢复条件为：
 * 1. 缓存正文与当前（已从服务端载入的）正文相同 → 无需恢复，丢弃
 * 2. 编辑态且缓存基线与当前服务端基线不同 → 服务端已另行更新，丢弃
 * 3. 其余情况恢复（新建态始终走这条，编辑态即「本地有未保存的更新」）
 *
 * - 已有帖：按 owner + name 存入 localStorage，跨标签共享恢复能力
 * - 新建态（name 为空）：存入当前标签页的 sessionStorage，避免多个新建页互相覆盖
 * - `raw` 必须可写（computed get/set 或 ref），恢复时会赋值
 */
export interface BbsContentCache {
  /** Halo 用户 metadata.name；防止同一浏览器中的不同账号互相恢复正文 */
  owner: string
  name: string
  content?: string
  /** 产生这份本地修改时所基于的服务端内容时间，等价于官方缓存里的快照版本基线 */
  sourceUpdatedAt?: string
  /** 缓存写入时间（ISO 8601），编辑态与服务端更新时间比较用 */
  savedAt: string
}

export function useContentCache(
  storageKey: string,
  name: Ref<string | undefined>,
  raw: Ref<string>,
  /** 服务端内容最后更新时间（lastEditTime / creationTimestamp）；新建态不传 */
  serverUpdatedAt: Ref<string | undefined>,
  /** 当前 Halo 用户 metadata.name；未就绪时禁用缓存读写 */
  owner: Ref<string | undefined>
) {
  const caches = useLocalStorage<BbsContentCache[]>(storageKey, [])
  const newDraftCache = useSessionStorage<BbsContentCache | null>(`${storageKey}:new`, null)
  const pendingCache = shallowRef<BbsContentCache>()

  function matches(cache: BbsContentCache, cacheOwner: string, cacheName: string) {
    return cache.owner === cacheOwner && cache.name === cacheName
  }

  function findStoredCache(cacheOwner: string, cacheName: string) {
    if (!cacheName) {
      // 新建稿只在本标签页的 sessionStorage；localStorage 只存已落库帖子的编辑缓存。
      return newDraftCache.value && matches(newDraftCache.value, cacheOwner, '')
        ? newDraftCache.value
        : undefined
    }
    return caches.value.find((cache) => matches(cache, cacheOwner, cacheName))
  }

  function removeLocalCache(cacheOwner: string, cacheName: string) {
    const index = caches.value.findIndex((cache) => matches(cache, cacheOwner, cacheName))
    if (index > -1) {
      caches.value.splice(index, 1)
    }
  }

  function upsertLocalCache(next: BbsContentCache) {
    removeLocalCache(next.owner, next.name)
    caches.value.push({ ...next })
  }

  /**
   * savedAt 同时承担恢复时的先后关系判断，不能只信任客户端时钟：
   * 当前编辑必然发生在已加载的服务端版本之后，因此至少要比该基线晚 1ms。
   */
  function createCacheSavedAt() {
    let cacheTime = Date.now()
    const serverTime = Date.parse(serverUpdatedAt.value || '')
    if (!Number.isNaN(serverTime) && cacheTime <= serverTime) {
      cacheTime = serverTime + 1
    }
    return new Date(cacheTime).toISOString()
  }

  const currentCache = computed<BbsContentCache | undefined>(() => {
    const cacheOwner = owner.value
    if (!cacheOwner) {
      return undefined
    }
    const cacheName = name.value || ''
    if (pendingCache.value && matches(pendingCache.value, cacheOwner, cacheName)) {
      return pendingCache.value
    }
    return findStoredCache(cacheOwner, cacheName)
  })

  function flushPendingCache() {
    const pending = pendingCache.value
    if (!pending) {
      return
    }
    if (!pending.name) {
      newDraftCache.value = { ...pending }
      pendingCache.value = undefined
      return
    }
    const cache = caches.value.find((item) => matches(item, pending.owner, pending.name))
    if (cache) {
      cache.content = pending.content
      cache.sourceUpdatedAt = pending.sourceUpdatedAt
      cache.savedAt = pending.savedAt
    } else {
      caches.value.push({ ...pending })
    }
    pendingCache.value = undefined
  }

  /** 正文写入 500ms 防抖落缓存；只由编辑器真实 update 事件调用。 */
  const { start: startCacheTimer, stop: stopCacheTimer, isPending } = useTimeoutFn(
    flushPendingCache,
    500,
    { immediate: false }
  )

  function handleSetContentCache() {
    const cacheOwner = owner.value
    if (!cacheOwner) {
      return
    }
    // 在事件发生时固定 owner/name/content，避免异步创建帖子后写到错误的 key。
    pendingCache.value = {
      owner: cacheOwner,
      name: name.value || '',
      content: raw.value,
      sourceUpdatedAt: serverUpdatedAt.value,
      savedAt: createCacheSavedAt(),
    }
    if (isPending.value) {
      stopCacheTimer()
    }
    startCacheTimer()
  }

  /**
   * 进入编辑器时检查恢复（在帖子内容加载完成后调用）。
   * 对齐 Halo 官方：恢复后立即移除原缓存。程序性恢复本身不能获得自动保存资格，
   * 用户再次真实编辑时才由 handleSetContentCache 创建新缓存。
   */
  function handleResetCache() {
    const cacheOwner = owner.value
    if (!cacheOwner) {
      return false
    }
    const cache = findStoredCache(cacheOwner, name.value || '')
    if (!cache) {
      return false
    }
    // 与服务端已载入正文相同：上次保存已落库，不要弹「已恢复」
    if (cache.content === raw.value) {
      handleClearCache()
      return false
    }
    if (name.value && serverUpdatedAt.value) {
      // 基线时间不同说明服务端内容已另行更新，本地缓存不能直接套用。
      // 快照层的并发写入另有 /content 的 version 判定兜底（冲突时后端分叉新版本）。
      const sourceChanged = cache.sourceUpdatedAt !== serverUpdatedAt.value
      if (sourceChanged) {
        handleClearCache()
        return false
      }
    }
    Toast.info('已恢复上次未保存的内容')
    raw.value = cache.content || ''
    handleClearCache()
    return true
  }

  function handleClearCache(targetName: string = name.value || '') {
    const cacheOwner = owner.value
    if (!cacheOwner) {
      return
    }
    if (pendingCache.value && matches(pendingCache.value, cacheOwner, targetName)) {
      pendingCache.value = undefined
      if (isPending.value) {
        stopCacheTimer()
      }
    }
    if (!targetName && newDraftCache.value && matches(newDraftCache.value, cacheOwner, '')) {
      newDraftCache.value = null
      return
    }
    if (targetName) {
      removeLocalCache(cacheOwner, targetName)
    }
  }

  /** Console / UC 新建草稿落库后，把请求期间的新输入从空 name 迁移到真实帖子 name。 */
  function handleMoveCache(fromName: string, toName: string) {
    const cacheOwner = owner.value
    if (!cacheOwner || fromName === toName) {
      return
    }
    const source = findStoredCache(cacheOwner, fromName)
    if (pendingCache.value && matches(pendingCache.value, cacheOwner, fromName)) {
      pendingCache.value = { ...pendingCache.value, name: toName }
    }
    if (!source) {
      return
    }
    handleClearCache(fromName)
    upsertLocalCache({ ...source, name: toName })
  }

  /**
   * 保存请求发出后若又有输入，服务端刚落库的是旧请求快照。本地缓存虽然更晚，
   * 物理写入时间却可能早于请求响应中的 lastEditTime；把它重定位到新服务端基线之后，
   * 避免刷新页面时被误判为陈旧缓存。
   */
  function handleRebaseCache(targetName: string = name.value || '') {
    const cacheOwner = owner.value
    if (!cacheOwner) {
      return
    }
    const savedAt = createCacheSavedAt()
    if (pendingCache.value && matches(pendingCache.value, cacheOwner, targetName)) {
      pendingCache.value = {
        ...pendingCache.value,
        sourceUpdatedAt: serverUpdatedAt.value,
        savedAt,
      }
    }
    if (!targetName && newDraftCache.value && matches(newDraftCache.value, cacheOwner, '')) {
      newDraftCache.value = {
        ...newDraftCache.value,
        sourceUpdatedAt: serverUpdatedAt.value,
        savedAt,
      }
      return
    }
    const stored = caches.value.find((cache) => matches(cache, cacheOwner, targetName))
    if (stored) {
      stored.sourceUpdatedAt = serverUpdatedAt.value
      stored.savedAt = savedAt
    }
  }

  // 路由切换或关闭页面前先把尚未到 500ms 的内容落入对应存储。
  const onBeforeUnload = () => flushPendingCache()
  window.addEventListener('beforeunload', onBeforeUnload)
  onBeforeUnmount(() => {
    stopCacheTimer()
    flushPendingCache()
    window.removeEventListener('beforeunload', onBeforeUnload)
  })

  return {
    currentCache,
    handleSetContentCache,
    handleResetCache,
    handleClearCache,
    handleMoveCache,
    handleRebaseCache,
  }
}
