<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { refDebounced } from '@vueuse/core'
import { ucApiClient } from '@halo-dev/api-client'
import { FormType, stores, utils } from '@halo-dev/ui-shared'
import type { AxiosRequestConfig } from 'axios'
import {
  VButton,
  VModal,
  VSpace,
  Toast,
  IconSave,
  IconSettings,
  IconSendPlaneFill,
  IconBookRead,
  IconCalendar,
  IconCharacterRecognition,
  IconFolder,
  IconLink,
  IconMessage,
  IconTimerLine,
} from '@halo-dev/components'
import RiMegaphoneLine from '~icons/ri/megaphone-line'
import RiHistoryLine from '~icons/ri/history-line'
import PostEditorFrame from '@/shared/PostEditorFrame.vue'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import PostDetailPanel from '@/shared/PostDetailPanel.vue'
import { publicApi, ucApi } from '@/api/bbs'
import { BBS_TYPE_LABELS, bbsStatusText } from '@/utils/post-labels'
import { contentStats } from '@/utils/content-stats'
import { useSlugify } from '@/composables/use-slugify'
import { useSaveKeybinding } from '@/composables/use-save-keybinding'
import { useSessionKeepAlive } from '@/composables/use-session-keep-alive'
import { useEditorSaveState } from '@/composables/use-editor-save-state'
import {
  defaultPostForm,
  postFormFrom,
  type BbsPost,
  type CategoryVo,
  type PostDetailItem,
  type PostRequest,
} from '@/types/bbs'

/**
 * UC 发帖/编辑页：共用 PostEditorFrame + PostSettingForm（managed=false，
 * 无公告/置顶特权字段），走 UC API。
 *
 * 保存体系对齐官方 UC 文章编辑器（Ctrl+S / 自动保存 / 本地缓存 / 会话保活），
 * 首次保存会静默创建服务端 DRAFT，普通保存不提交，发布/重提走显式 submit。
 * 已发布帖的静默保存写入 Halo 核心 headSnapshot，公开端仍读 releaseSnapshot。
 */
const route = useRoute()
const router = useRouter()
const currentUserStore = stores.currentUser()
const cacheOwner = computed(() => currentUserStore.currentUser?.user.metadata.name)
const editName = ref<string>((route.query.name as string) || '')
const saving = ref(false)
const editorReady = ref(false)
const editorLoadFailed = ref(false)
const settingVisible = ref(false)
const categories = ref<CategoryVo[]>([])

const formData = ref(defaultPostForm())

// 当前发布实体状态；PUBLISHED 修改稿的子状态单独由 loadedDraftPhase 表达。
const loadedPhase = ref<string>('DRAFT')
const loadedDraftPhase = ref<string | undefined>(undefined)
const snapshotVersion = ref<number>()

// 服务端内容最后更新时间：编辑态恢复本地缓存的判定基准
const serverUpdatedAt = ref<string | undefined>(undefined)

// 详情面板的只读元数据（新建帖为空，保存后部分回填）
const loadedMeta = ref<{
  publishTime?: string
  totalComments?: number
  /** 前台访问链接（release slug），仅已发布帖有意义 */
  permalink?: string
}>({})

// 字数统计防抖：击键只触发响应写入，300ms 后才对全文跑统计正则
const contentDebounced = refDebounced(computed(() => formData.value.content), 300)

/**
 * 右侧「详情」页签条目。形态对齐官方编辑器详情页签（灰底卡片 + 角标图标）：
 * 官方四件套（字符数/词数/发布时间/访问链接）打头，BBS 特有的状态/类型/分类/
 * 评论数随后。UC 不显示作者（本人帖子）；无值条目由面板隐藏（官方同款）。
 */
const detailItems = computed<PostDetailItem[]>(() => {
  const f = formData.value
  const category = categories.value.find((c) => c.name === f.categoryName)
  const meta = loadedMeta.value
  const stats = contentStats(contentDebounced.value)
  // 只有已发布帖才有公开访问链接
  const publishedLink = loadedPhase.value === 'PUBLISHED' ? meta.permalink : undefined
  const items: PostDetailItem[] = [
    { label: '字符数', value: String(stats.chars), icon: IconCharacterRecognition, half: true },
    { label: '词数', value: String(stats.words), icon: IconCharacterRecognition, half: true },
    {
      label: '发布时间',
      value: meta.publishTime ? utils.date.format(meta.publishTime) : undefined,
      icon: IconCalendar,
    },
    { label: '访问链接', value: publishedLink, href: publishedLink, icon: IconLink },
    {
      label: '状态',
      value: editName.value
        ? bbsStatusText(loadedPhase.value, loadedDraftPhase.value)
        : '未发布（未保存）',
      icon: IconTimerLine,
      half: true,
    },
    { label: '类型', value: BBS_TYPE_LABELS[f.type], icon: IconBookRead, half: true },
    { label: '分类', value: category?.displayName, icon: IconFolder, half: true },
  ]
  if (editName.value) {
    items.push({
      label: '评论数',
      value: meta.totalComments != null ? String(meta.totalComments) : undefined,
      icon: IconMessage,
      half: true,
    })
  }
  return items
})

/**
 * 标题 → 别名实时联想（新建时），行为对齐官方文章：编辑已有帖子改标题不动别名。
 * 设置弹窗内那份只在弹窗打开时生效，标题输入框却一直在用，故这里再挂一份。
 */
const { handleGenerateSlug } = useSlugify(
  computed(() => formData.value.title),
  computed({
    get: () => formData.value.slug,
    set: (value) => {
      formData.value.slug = value
    },
  }),
  computed(() => !editName.value),
  FormType.POST
)
// 设置弹窗内的保存走表单校验：校验不通过不会触发 doSave
const settingForm = ref<InstanceType<typeof PostSettingForm>>()
const settingModal = ref<InstanceType<typeof VModal>>()
const pendingSubmit = ref(false)

/** 自动保存条件与 Halo UC 一致：支持新建和已发布内容（后者只更新 head 工作稿）。 */
function autoSaveIfPossible() {
  if (!editorReady.value || settingVisible.value || saveInFlight.value) {
    return
  }
  return handleSave({ mute: true })
}

// —— 保存状态机：串行队列 + 脏守卫 + 内容缓存 / 自动保存 + 保存结算（与 Console 编辑器共用） ——
const {
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
} = useEditorSaveState({
  storageKey: 'bbs-uc-post-content-cache',
  editName,
  formData,
  serverUpdatedAt,
  cacheOwner,
  autoSave: autoSaveIfPossible,
})

async function fetchCategories() {
  try {
    // 走公开接口取启用中的分类（UC 用户无 Console 权限）
    const { data } = await publicApi.listCategories()
    categories.value = data || []
  } catch {
    /* 忽略：分类加载失败不阻塞发帖 */
  }
}

async function loadPost(name: string) {
  try {
    const { data: post } = await ucApi.getMine(name)
    if (post.locked) {
      Toast.warning('该帖子已被锁定，无法编辑')
      router.push({ name: 'BbsUcPosts' })
      return false
    }
    // 回填真实类型：讨论 / 问答可互改；公告在表单中锁定展示（后端亦强制保持）；
    // UC 侧无置顶特权，pinned / pinPriority 恒为默认值
    formData.value = postFormFrom(post, { managed: false })
    loadedPhase.value = post.phase || 'DRAFT'
    loadedDraftPhase.value = post.draftPhase
    snapshotVersion.value = post.snapshotVersion
    serverUpdatedAt.value = [post.lastEditTime, post.creationTimestamp]
      .filter(Boolean)
      .sort()
      .pop()
    loadedMeta.value = {
      publishTime: post.publishTime,
      totalComments: post.totalCommentCount,
      permalink: post.permalink,
    }
    return true
  } catch (error) {
    /* 全局拦截器已提示，不二次 Toast */
    // 同步异常（如字段引用错误）也会被这里吞掉，打到控制台便于定位
    console.error('[bbs] 编辑器帖子加载失败', error)
    editorLoadFailed.value = true
    return false
  }
}

function buildBody(): PostRequest {
  const f = formData.value
  return {
    title: f.title,
    // 别名由 useSlugify 在新建时随标题联想生成，此处直接提交。
    // 提交/更新路径：唯一性由设置表单内联预检 + 后端报错拦截，不做静默改写。
    // 「未命名」兜底标题防重顺延时别名随之追加同一编号（服务端落库、响应回填）。
    // 注：UC 建稿（createOwnedDraft）不做别名查重，对齐官方 UC——冲突在提交时报错。
    slug: f.slug.trim(),
    type: f.type,
    categoryName: f.categoryName,
    excerpt: f.excerpt,
    autoExcerpt: f.autoExcerpt,
    content: f.content,
  }
}

/**
 * 编辑已有帖子时的保存：正文先走 /content（带 version 做并发检测，与服务端 head
 * 不一致时后端分叉新版本而不是覆盖对方），元数据再走 /{name}（不带正文，避免只改
 * 标题也在版本链里留一条内容相同的记录）。对齐官方编辑器的两条通道。
 */
async function saveExisting(body: PostRequest) {
  const { data: afterContent } = await ucApi.saveContent(editName.value, {
    version: snapshotVersion.value,
    raw: body.content,
    content: body.content,
    rawType: 'html',
  })
  syncSnapshotState(afterContent)
  try {
    const { data } = await ucApi.saveDraft(editName.value, { ...body, content: undefined })
    syncSnapshotState(data)
    return data
  } catch (error) {
    // 正文已保存：说清部分成功再交给全局拦截器，不做回滚（两通道无事务，与官方一致）
    Toast.warning('正文已保存，标题/分类等设置未保存，请重试')
    throw error
  }
}

function syncSnapshotState(post: BbsPost) {
  snapshotVersion.value = post.status?.headSnapshotVersion
}

async function handleUploadImage(file: File, options?: AxiosRequestConfig) {
  const { data } = await ucApiClient.storage.attachment.uploadAttachmentForUc({ file }, options)
  return data
}

/**
 * 保存，对齐官方 UC PostEditor.handleSave：
 * - 新建时直接创建服务端 DRAFT，不要求先打开设置弹窗
 * - DRAFT / REJECTED / PENDING 普通保存不改变流程状态
 * - PUBLISHED 普通保存只更新工作稿，不改变前台发布副本
 * - 静默模式供自动保存使用，不弹 Toast
 */
async function handleSave(options?: { mute?: boolean }) {
  const mute = !!options?.mute
  if (!editorReady.value) {
    return
  }
  if (!mute && saving.value) {
    return
  }
  const shouldCloseSetting = !mute && settingVisible.value
  if (mute && saveInFlight.value) {
    return
  }
  // 已有帖且毫无改动：不发请求——无改动的保存不该刷新 lastEditTime（会把帖子
  // 误标成「已编辑」），也没有元数据需要落库。与 Console 编辑器对齐。
  if (editName.value && !isDirty()) {
    if (shouldCloseSetting) {
      settingSnapshot.value = ''
      settingVisible.value = false
    }
    if (!mute) {
      Toast.success('保存成功')
    }
    return
  }
  if (!mute) {
    saving.value = true
  }
  try {
    await enqueueOperation(async () => {
      if (!formData.value.title.trim()) {
        formData.value.title = '未命名'
      }

      const cacheName = editName.value || ''
      const sentSnapshot = serializeForm()
      const body = buildBody()
      let savedSnapshotAtRequest = sentSnapshot
      let newName: string | undefined

      if (editName.value) {
        const data = await saveExisting(body)
        loadedPhase.value = data.spec?.phase || loadedPhase.value
        loadedDraftPhase.value = data.spec?.draft?.phase
        serverUpdatedAt.value =
          data.spec?.draft?.lastEditTime ||
          data.spec?.lastEditTime ||
          data.metadata.creationTimestamp ||
          new Date().toISOString()
        savedSnapshotAtRequest = canonicalizeSavedSnapshot(
          sentSnapshot,
          data.spec?.draft?.slug || data.spec?.slug
        )
      } else {
        const { data } = await ucApi.createDraft(body)
        syncSnapshotState(data)
        newName = data.metadata.name
        loadedPhase.value = data.spec?.phase || 'DRAFT'
        serverUpdatedAt.value =
          data.spec?.lastEditTime || data.metadata.creationTimestamp || new Date().toISOString()
        savedSnapshotAtRequest = canonicalizeSavedSnapshot(
          sentSnapshot,
          data.spec?.slug,
          data.spec?.title
        )
        // 请求期间产生的新正文仍写在空 name 下，创建成功后迁移到真实帖子。
        if (serializeForm() !== savedSnapshotAtRequest) {
          handleMoveCache(cacheName, newName)
        }
        editName.value = newName
      }

      settleSavedSnapshot(savedSnapshotAtRequest, cacheName)
      // 与 Halo 一致：首次服务端建稿后立即把编辑器 URL 切到实体 name。
      if (newName) {
        await router.replace({ name: 'BbsUcPostEditor', query: { name: newName } })
      }
      if (shouldCloseSetting) {
        settingSnapshot.value = ''
        settingVisible.value = false
      }
    })
    if (!mute) {
      Toast.success('保存成功')
    }
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    if (!mute) {
      saving.value = false
    }
  }
}

/**
 * 显式提交：新建内容先创建 DRAFT，再调用 submit；已有草稿/驳回稿/待审稿直接提交，
 * 已发布帖也只在这条明确的用户操作中提交修改。成功且没有后续输入时返回列表。
 */
async function handleSubmit() {
  if (!editorReady.value || saving.value) {
    return
  }
  const shouldCloseSetting = settingVisible.value
  saving.value = true
  try {
    let hasNewerChanges = false
    await enqueueOperation(async () => {
      if (!formData.value.title.trim()) {
        formData.value.title = '未命名'
      }
      if (!formData.value.slug.trim()) {
        handleGenerateSlug(true)
      }

      const cacheName = editName.value || ''
      const sentSnapshot = serializeForm()
      const body = buildBody()
      let postName = editName.value

      if (!postName) {
        const { data: draft } = await ucApi.createDraft(body)
        syncSnapshotState(draft)
        postName = draft.metadata.name
        loadedPhase.value = draft.spec?.phase || 'DRAFT'
        serverUpdatedAt.value =
          draft.spec?.lastEditTime ||
          draft.metadata.creationTimestamp ||
          new Date().toISOString()
        const createdSnapshot = canonicalizeSavedSnapshot(
          sentSnapshot,
          draft.spec?.slug,
          draft.spec?.title
        )
        // submit 紧跟 create，沿用服务端已归一化的别名与防重顺延后的标题，
        // 避免空别名被二次随机生成、旧标题把顺延结果盖回去。
        if (draft.spec?.slug) {
          body.slug = draft.spec.slug
        }
        if (draft.spec?.title) {
          body.title = draft.spec.title
        }
        if (serializeForm() !== createdSnapshot) {
          handleMoveCache(cacheName, postName)
        }
        editName.value = postName
        // 创建成功即先结算 DRAFT；即使后续 submit 失败，也不能把已落库草稿当成没保存。
        settleSavedSnapshot(createdSnapshot, cacheName)
        if (shouldCloseSetting) {
          // submit 若随后失败，关闭弹窗最多回到已落库的草稿，不回到建稿前状态。
          settingSnapshot.value = createdSnapshot
        }
        await router.replace({ name: 'BbsUcPostEditor', query: { name: postName } })
      } else if (isDirty()) {
        // 已有帖：正文先经 /content 落库（带并发检测）。元数据不走 saveDraft——
        // saveDraft 遇待审/驳回稿会先留痕「撤回审核」，提交场景只需要 submit 一次
        // 处理元数据与状态，避免时间线多出一条撤回记录（对齐旧 submit 行为）。
        // 毫无改动时跳过正文保存：无改动不落快照（服务端也有相等性兜底）。
        const { data: afterContent } = await ucApi.saveContent(postName, {
          version: snapshotVersion.value,
          raw: body.content,
          content: body.content,
          rawType: 'html',
        })
        syncSnapshotState(afterContent)
      }

      const { data } = await ucApi.submit(postName, { ...body, content: undefined })
      syncSnapshotState(data)
      loadedPhase.value = data.spec?.phase || loadedPhase.value
      loadedDraftPhase.value = data.spec?.draft?.phase
      // 详情面板回填提交后的元数据
      if (data.spec?.publishTime) {
        loadedMeta.value.publishTime = data.spec.publishTime
      }
      serverUpdatedAt.value =
        data.spec?.draft?.lastEditTime ||
        data.spec?.lastEditTime ||
        data.metadata.creationTimestamp ||
        new Date().toISOString()
      // 标题同样归一化：新建即提交时服务端可能已把兜底标题防重顺延，
      // 不归一化会被误判成「提交后又产生了未保存修改」
      const submittedSnapshot = canonicalizeSavedSnapshot(
        sentSnapshot,
        data.spec?.draft?.slug || data.spec?.slug,
        data.spec?.draft?.title || data.spec?.title
      )
      hasNewerChanges = settleSavedSnapshot(submittedSnapshot, cacheName)
      if (shouldCloseSetting) {
        settingSnapshot.value = ''
        settingVisible.value = false
      }
      pendingSubmit.value = false
    })

    Toast.success('提交成功')
    if (hasNewerChanges) {
      // 提交请求后又有输入：留在编辑器，避免后续改动被静默丢弃
      await router.replace({ name: 'BbsUcPostEditor', query: { name: editName.value } })
    } else {
      await router.push({ name: 'BbsUcPosts' })
    }
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    saving.value = false
  }
}

/** 标题与正文都属于编辑器更新：写入恢复缓存，并从最后一次输入重新计时自动保存。 */
function handleEditorUpdate() {
  handleSetContentCache()
  scheduleAutoSave()
}

// Ctrl+S / ⌘S：新建即创建服务端草稿；已发布内容保存到独立工作稿。
useSaveKeybinding(() => {
  if (!editorReady.value || settingVisible.value) {
    return
  }
  handleSave()
})

// 会话保活：长文编辑期间防 session 过期导致保存 401
useSessionKeepAlive()

const settingSnapshot = ref('')

function openSetting(submit = false) {
  if (!editorReady.value || saving.value) {
    return
  }
  pendingSubmit.value = submit
  settingSnapshot.value = JSON.stringify(formData.value)
  settingVisible.value = true
}

function closeSetting() {
  // 保存请求已受理后关闭弹窗，不应把队列尚未读取的设置值回滚为打开弹窗前的快照。
  if (!saving.value && settingSnapshot.value) {
    formData.value = JSON.parse(settingSnapshot.value)
    // 快照一次性：关闭即失效——否则下次「缺分类直开弹窗」会把更晚写的正文
    // 回滚到这份陈旧快照上（静默丢正文）。与 Console PostEditorView 对齐。
  }
  settingSnapshot.value = ''
  pendingSubmit.value = false
  settingVisible.value = false
}

function requestCloseSetting() {
  settingModal.value?.close()
}

/** 弹窗内按钮先跑完整表单校验，再区分“保存”和“正式提交”。 */
function requestSettingAction(submit: boolean) {
  pendingSubmit.value = submit
  settingForm.value?.submit()
}

function onSettingSubmit() {
  if (pendingSubmit.value) {
    handleSubmit()
  } else {
    handleSave()
  }
}

function onSaveClick() {
  handleSave()
}

/** 正式提交前必须补齐发布元数据；已有且完整时可直接提交。 */
function onSubmitClick() {
  if (!editName.value || !formData.value.categoryName || !formData.value.slug.trim()) {
    openSetting(true)
    return
  }
  handleSubmit()
}

// 统一两字文案：无论新建 / 驳回重提 / 修改稿提交都叫「提交」，
// 状态差异由状态列与审核记录表达，动词不背着状态
const submitLabel = '提交'

onMounted(async () => {
  try {
    if (!currentUserStore.currentUser) {
      await currentUserStore.fetchCurrentUser()
    }
  } catch {
    /* 获取失败时仅禁用本地缓存；页面其余功能仍由接口权限兜底 */
  }
  await fetchCategories()
  let contentLoaded = true
  if (editName.value) {
    contentLoaded = await loadPost(editName.value)
  }
  if (!contentLoaded) {
    return
  }
  markSaved()
  // 内容加载完成后检查本地缓存恢复（比服务端新才恢复）
  handleResetCache()
  editorReady.value = true
})
</script>

<template>
  <div v-if="editorLoadFailed" class="bbs-editor-loading">
    <span>帖子加载失败，请返回列表后重试</span>
    <VButton size="sm" @click="router.push({ name: 'BbsUcPosts' })">返回我的帖子</VButton>
  </div>

  <div v-else-if="!editorReady" class="bbs-editor-loading">正在加载编辑器…</div>

  <PostEditorFrame
    v-else
    v-model:title="formData.title"
    v-model:raw="formData.content"
    :page-title="editName ? '编辑帖子' : '写帖子'"
    :upload-image="handleUploadImage"
    @update="handleEditorUpdate"
  >
    <template #icon>
      <RiMegaphoneLine />
    </template>
    <template #actions>
      <VSpace>
        <VButton
          v-if="editName"
          size="sm"
          :disabled="!editorReady || saving"
          @click="router.push({ name: 'BbsUcPostSnapshots', query: { name: editName } })"
        >
          <template #icon><RiHistoryLine /></template>
          历史版本
        </VButton>
        <VButton
          size="sm"
          :disabled="!editorReady"
          :loading="saving"
          @click="onSaveClick"
        >
          <template #icon><IconSave /></template>
          保存
        </VButton>
        <VButton
          v-if="editName"
          size="sm"
          :disabled="!editorReady || saving"
          @click="openSetting"
        >
          <template #icon><IconSettings /></template>
          设置
        </VButton>
        <VButton
          type="secondary"
          :disabled="!editorReady"
          :loading="saving"
          @click="onSubmitClick"
        >
          <template #icon><IconSendPlaneFill /></template>
          {{ submitLabel }}
        </VButton>
      </VSpace>
    </template>
    <template #details>
      <PostDetailPanel :items="detailItems" />
    </template>
  </PostEditorFrame>

  <VModal
    v-if="settingVisible"
    ref="settingModal"
    title="帖子设置"
    :width="680"
    mount-to-body
    @close="closeSetting"
  >
    <PostSettingForm
      ref="settingForm"
      v-model="formData"
      :categories="categories"
      :managed="false"
      :post-name="editName || undefined"
      @submit="onSettingSubmit"
    />
    <template #footer>
      <VSpace>
        <VButton
          type="secondary"
          :loading="saving"
          @click="requestSettingAction(true)"
        >
          {{ submitLabel }}
        </VButton>
        <VButton
          :loading="saving"
          @click="requestSettingAction(false)"
        >保存</VButton>
        <VButton @click="requestCloseSetting">关闭</VButton>
      </VSpace>
    </template>
  </VModal>

</template>

<!-- bbs-editor-loading 见 styles/tokens.css -->
