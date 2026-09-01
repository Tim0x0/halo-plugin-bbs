import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import { useContentCache } from './use-content-cache'
import { useAutoSave } from './use-auto-save'
import type { PostFormState } from '@/types/bbs'

/**
 * 编辑器保存状态机（Console / UC 编辑器共用）：串行队列、未保存离开防护、
 * 内容缓存 + 自动保存、保存快照结算。两侧仅存储键与自动保存条件不同。
 *
 * 这是插件里最易漂移的逻辑（快照结算、防丢正文），故两侧不允许各自实现，
 * 只能经此组合式函数消费。
 */
export function useEditorSaveState(options: {
  storageKey: string
  editName: Ref<string>
  formData: Ref<PostFormState>
  serverUpdatedAt: Ref<string | undefined>
  cacheOwner: Ref<string | undefined>
  /** 自动保存触发时的落库判定（条件留在回调内，对齐官方） */
  autoSave: () => void | Promise<void>
}) {
  const { storageKey, editName, formData, serverUpdatedAt, cacheOwner, autoSave } = options

  // ---- 串行队列：手动操作等待进行中的自动保存，不会被静默吞掉 ----
  const saveInFlight = ref(false)
  let operationTail: Promise<void> = Promise.resolve()
  let queuedOperationCount = 0

  function enqueueOperation(operation: () => Promise<void>) {
    queuedOperationCount += 1
    saveInFlight.value = true
    const result = operationTail.then(operation, operation)
    operationTail = result.catch(() => undefined)
    return result.finally(() => {
      queuedOperationCount -= 1
      saveInFlight.value = queuedOperationCount > 0
    })
  }

  // ---- 脏守卫：序列化比对判脏，有改动时拦截刷新 / 关闭（自动保存尽力而为，
  //      离开瞬间未必完成，故保留浏览器拦截兜底） ----
  function serializeForm() {
    return JSON.stringify(formData.value)
  }

  let savedSnapshot = serializeForm()

  function markSaved(snapshot = serializeForm()) {
    savedSnapshot = snapshot
  }

  /** 当前表单相对上次结算的保存快照是否有改动（脏检查）。 */
  function isDirty() {
    return serializeForm() !== savedSnapshot
  }

  function onBeforeUnload(e: BeforeUnloadEvent) {
    if (serializeForm() !== savedSnapshot) {
      e.preventDefault()
      e.returnValue = ''
    }
  }

  window.addEventListener('beforeunload', onBeforeUnload)
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
  })

  /**
   * 把服务端归一化后的 slug（以及创建时防重顺延的标题）纳入本次保存快照，
   * 同时不覆盖响应期间的新输入：用户若已经改了别名 / 标题，不能被服务端
   * 对旧请求的响应盖回去。
   */
  function canonicalizeSavedSnapshot(
    sentSnapshot: string,
    serverSlug?: string,
    serverTitle?: string
  ) {
    const sentForm = JSON.parse(sentSnapshot) as PostFormState
    if (serverSlug && sentForm.slug !== serverSlug) {
      if (formData.value.slug === sentForm.slug) {
        formData.value.slug = serverSlug
      }
      sentForm.slug = serverSlug
    }
    if (serverTitle && sentForm.title !== serverTitle) {
      if (formData.value.title === sentForm.title) {
        formData.value.title = serverTitle
      }
      sentForm.title = serverTitle
    }
    return JSON.stringify(sentForm)
  }

  // ---- 内容缓存 + 自动保存（对齐官方，详见各 composable 注释） ----
  // 必须用可写 computed：loadPost 会整体替换 formData.value，toRef(.value, 'content')
  // 会钉死在旧对象上，恢复缓存写不进当前表单。
  const rawRef = computed({
    get: () => formData.value.content,
    set: (value) => {
      formData.value.content = value
    },
  })

  const {
    currentCache,
    handleSetContentCache,
    handleResetCache,
    handleClearCache,
    handleMoveCache,
    handleRebaseCache,
  } = useContentCache(storageKey, editName, rawRef, serverUpdatedAt, cacheOwner)

  const { scheduleAutoSave } = useAutoSave(currentCache, rawRef, autoSave)

  /** 用实际发出的快照结算保存结果；响应期间的新输入继续保持 dirty 与缓存。 */
  function settleSavedSnapshot(savedSnapshotAtRequest: string, previousCacheName: string) {
    const hasNewerChanges = serializeForm() !== savedSnapshotAtRequest
    markSaved(savedSnapshotAtRequest)
    const currentCacheName = editName.value || previousCacheName
    if (hasNewerChanges) {
      handleRebaseCache(currentCacheName)
      scheduleAutoSave()
    } else {
      handleClearCache(currentCacheName)
      if (previousCacheName !== editName.value) {
        handleClearCache(previousCacheName)
      }
    }
    return hasNewerChanges
  }

  return {
    saveInFlight,
    enqueueOperation,
    serializeForm,
    markSaved,
    isDirty,
    canonicalizeSavedSnapshot,
    handleSetContentCache,
    handleResetCache,
    handleMoveCache,
    scheduleAutoSave,
    settleSavedSnapshot,
  }
}
