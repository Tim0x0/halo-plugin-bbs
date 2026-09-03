<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { refDebounced } from '@vueuse/core'
import { consoleApiClient } from '@halo-dev/api-client'
import { FormType, stores, utils } from '@halo-dev/ui-shared'
import type { AxiosRequestConfig } from 'axios'
import {
  VButton,
  VModal,
  VSpace,
  Toast,
  IconSave,
  IconEye,
  IconSettings,
  IconSendPlaneFill,
  IconBookRead,
  IconCalendar,
  IconCharacterRecognition,
  IconFolder,
  IconLink,
  IconMessage,
  IconTimerLine,
  IconUserFollow,
} from '@halo-dev/components'
import RiMegaphoneLine from '~icons/ri/megaphone-line'
import RiHistoryLine from '~icons/ri/history-line'
import PostEditorFrame from '@/shared/PostEditorFrame.vue'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import PostDetailPanel from '@/shared/PostDetailPanel.vue'
import { consoleApi } from '@/api/bbs'
import { BBS_TYPE_LABELS, bbsStatusText } from '@/utils/post-labels'
import { contentStats } from '@/utils/content-stats'
import type { PostDetailItem } from '@/types/bbs'
import { useSlugify } from '@/composables/use-slugify'
import { useSaveKeybinding } from '@/composables/use-save-keybinding'
import { useSessionKeepAlive } from '@/composables/use-session-keep-alive'
import { useEditorSaveState } from '@/composables/use-editor-save-state'
import {
  defaultPostForm,
  postFormFrom,
  type BbsPost,
  type CategoryVo,
  type PostRequest,
} from '@/types/bbs'

/**
 * Console 帖子/公告编辑页：共用 PostEditorFrame + PostSettingForm（managed=true，
 * 可设类型与置顶），走 Console API。
 *
 * 保存体系对齐官方文章编辑器：Ctrl+S / 自动保存 / 本地缓存 / 会话保活。
 * 已发布帖保存到 Halo 核心 headSnapshot，只有显式发布才切换 releaseSnapshot。
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

/**
 * 标题 → 别名实时联想（新建时），行为对齐官方文章：编辑已有帖子改标题不动别名。
 *
 * PostSettingForm 内部也挂了一份，但设置弹窗是 v-if 挂载的，关着的时候不生效——
 * 而标题输入框在编辑器顶部、平时就在用。所以这里再挂一份，边打标题边出别名。
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
// 设置弹窗内的保存/发布走表单校验：校验不通过不会触发 onSettingSubmit
const settingForm = ref<InstanceType<typeof PostSettingForm>>()
const settingModal = ref<InstanceType<typeof VModal>>()
const pendingPublish = ref(false)

// 当前帖子状态（对齐官方口径：DRAFT 一律显示「未发布」）
const loadedPhase = ref<string>('DRAFT')
// 编辑已发布帖时 formData.slug 可能是尚未发布的工作稿别名；预览必须仍打开 release 链接。
const publishedPermalink = ref<string>('')
const snapshotVersion = ref<number>()

// 详情面板的只读元数据（新建帖为空，保存后部分回填）
const loadedMeta = ref<{
  ownerName?: string
  publishTime?: string
  totalComments?: number
}>({})

// 字数统计防抖：击键只触发响应写入，300ms 后才对全文跑统计正则
const contentDebounced = refDebounced(computed(() => formData.value.content), 300)

/**
 * 右侧「详情」页签条目。形态对齐官方编辑器详情页签（灰底卡片 + 角标图标）：
 * 官方四件套（字符数/词数/发布时间/作者/访问链接）打头，BBS 特有的
 * 状态/类型/分类/评论数随后。无值条目由面板隐藏（官方同款），
 * 故作者/发布时间/评论数等元数据保存后才随回填出现。
 */
const detailItems = computed<PostDetailItem[]>(() => {
  const f = formData.value
  const category = categories.value.find((c) => c.name === f.categoryName)
  const meta = loadedMeta.value
  const stats = contentStats(contentDebounced.value)
  const items: PostDetailItem[] = [
    { label: '字符数', value: String(stats.chars), icon: IconCharacterRecognition, half: true },
    { label: '词数', value: String(stats.words), icon: IconCharacterRecognition, half: true },
    {
      label: '发布时间',
      value: meta.publishTime ? utils.date.format(meta.publishTime) : undefined,
      icon: IconCalendar,
    },
    { label: '作者', value: meta.ownerName, icon: IconUserFollow },
    {
      label: '访问链接',
      value: publishedPermalink.value || undefined,
      href: publishedPermalink.value || undefined,
      icon: IconLink,
    },
    {
      label: '状态',
      value: editName.value ? bbsStatusText(loadedPhase.value) : '未发布（未保存）',
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

// 服务端内容最后更新时间：编辑态恢复本地缓存的判定基准
const serverUpdatedAt = ref<string | undefined>(undefined)

/**
 * 自动保存条件（对齐官方把条件留在回调内的做法）：
 * - 设置弹窗开着不保存（对齐官方）
 * - 编辑已发布帖自动保存到工作稿，不影响前台版本
 * - 新建未选分类不建稿：分类必填是产品规则，静默跳过（本地缓存仍兜底）
 */
function autoSaveIfPossible() {
  if (!editorReady.value || settingVisible.value || saveInFlight.value) {
    return
  }
  if (editName.value) {
    return handleSave({ mute: true })
  } else if (formData.value.categoryName) {
    return handleSave({ mute: true })
  }
}

// —— 保存状态机：串行队列 + 脏守卫 + 内容缓存 / 自动保存 + 保存结算（与 UC 编辑器共用） ——
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
  storageKey: 'bbs-console-post-content-cache',
  editName,
  formData,
  serverUpdatedAt,
  cacheOwner,
  autoSave: autoSaveIfPossible,
})

async function fetchCategories() {
  try {
    const { data } = await consoleApi.listCategories()
    categories.value = data || []
  } catch {
    /* 忽略：分类加载失败不阻塞编辑 */
  }
}

async function loadPost(name: string) {
  try {
    const { data: post } = await consoleApi.getPost(name)
    formData.value = postFormFrom(post)
    loadedPhase.value = post.phase || 'DRAFT'
    snapshotVersion.value = post.snapshotVersion
    publishedPermalink.value = post.phase === 'PUBLISHED' ? post.permalink || '' : ''
    serverUpdatedAt.value = [post.lastEditTime, post.creationTimestamp]
      .filter(Boolean)
      .sort()
      .pop()
    loadedMeta.value = {
      ownerName: post.owner?.displayName,
      publishTime: post.publishTime,
      totalComments: post.totalCommentCount,
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
    // 更新路径：唯一性由设置表单内联预检 + 后端报错拦截，不做静默改写。
    // 创建路径有两处服务端改写（均响应回填，官方同款行为）：
    // ①「未命名」兜底标题防重顺延，别名随之追加同一编号；
    // ② 别名撞「发布占用」时自动追加随机后缀（对齐官方编辑器创建期行为）。
    slug: f.slug.trim(),
    type: f.type,
    categoryName: f.categoryName,
    excerpt: f.excerpt,
    autoExcerpt: f.autoExcerpt,
    content: f.content,
    pinned: f.pinned,
    pinPriority: f.pinPriority,
  }
}

/**
 * 编辑已有帖子时的保存：正文先走 /content（带 version 做并发检测，与服务端 head
 * 不一致时后端分叉新版本而不是覆盖对方），元数据再走 /{name}（不带正文，避免只改
 * 标题也在版本链里留一条内容相同的记录）。对齐官方编辑器的两条通道。
 */
async function saveExisting(body: PostRequest) {
  const { data: afterContent } = await consoleApi.saveContent(editName.value, {
    version: snapshotVersion.value,
    raw: body.content,
    content: body.content,
    rawType: 'html',
  })
  syncSnapshotState(afterContent)
  try {
    const { data } = await consoleApi.updatePost(editName.value, {
      ...body,
      content: undefined,
    })
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
  const { data } = await consoleApiClient.storage.attachment.uploadAttachmentForConsole(
    { file },
    options
  )
  return data
}

/**
 * 保存，对齐官方 PostEditor.handleSave：
 * - 标题为空自动补「未命名」，不拦截；正文允许为空
 * - 新建直接建未发布帖（不弹设置弹窗）；slug 为空按标题生成
 * - mute=true 时静默保存（自动保存 / Ctrl+S 前的预检场景共用），不弹 Toast
 */
async function handleSave(options?: { mute?: boolean }) {
  const mute = !!options?.mute
  if (!editorReady.value) {
    return
  }
  // saving 只表示已有手动操作；静默自动保存不会置它，因此发布撞上自动保存仍可排队。
  if (!mute && saving.value) {
    return
  }
  const shouldCloseSetting = !mute && settingVisible.value
  // 自动保存可合并；手动保存必须排队，不能因为另一个请求进行中而丢失。
  if (mute && saveInFlight.value) {
    return
  }
  // 已有帖且毫无改动：不发请求——无改动的保存不该刷新 lastEditTime（会把帖子
  // 误标成「已编辑」），也没有元数据需要落库。
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
      if (!editName.value && !formData.value.slug.trim()) {
        handleGenerateSlug(true)
      }
      // 请求体与快照必须在同一个同步区间采集，响应后才能准确判断是否又有输入。
      const sentSnapshot = serializeForm()
      const body = buildBody()
      let savedSnapshotAtRequest = sentSnapshot
      let newName: string | undefined
      if (editName.value) {
        const data = await saveExisting(body)
        savedSnapshotAtRequest = canonicalizeSavedSnapshot(
          sentSnapshot,
          data.spec?.draft?.slug || data.spec?.slug
        )
        serverUpdatedAt.value =
          data.spec?.draft?.lastEditTime ||
          data.spec?.lastEditTime ||
          data.metadata.creationTimestamp ||
          new Date().toISOString()
      } else {
        const { data } = await consoleApi.createPost(body, false)
        syncSnapshotState(data)
        // 标题/别名被服务端防重顺延时，统一以响应回填为准
        savedSnapshotAtRequest = canonicalizeSavedSnapshot(
          sentSnapshot,
          data.spec?.slug,
          data.spec?.title
        )
        newName = data.metadata.name
        // 请求期间产生的新正文仍以空 name 缓存，创建成功后迁移到真实帖子。
        if (serializeForm() !== savedSnapshotAtRequest) {
          handleMoveCache(cacheName, newName)
        }
        editName.value = newName
        loadedPhase.value = 'DRAFT'
        serverUpdatedAt.value =
          data.spec?.lastEditTime || data.metadata.creationTimestamp || new Date().toISOString()
      }

      settleSavedSnapshot(savedSnapshotAtRequest, cacheName)
      // 先结算已落库快照再改 URL；即使路由失败，也不能把成功保存误当失败。
      if (newName) {
        await router.replace({ name: 'BbsPostEditor', query: { name: newName } })
      }
      if (shouldCloseSetting) {
        // 静默保存可能在弹窗打开前已发出、打开后才返回；此时不能清掉取消快照。
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

/** 保存并发布（弹窗提交 / 编辑已有帖的发布按钮共用）。 */
async function handlePublish() {
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
      let savedSnapshotAtRequest = sentSnapshot
      let publishedPost: BbsPost
      if (editName.value) {
        // 毫无改动时直接发布：跳过正文与元数据两次保存请求，避免重新发布前
        // 无谓刷新 lastEditTime（会把帖子误标成「已编辑」）。
        if (isDirty()) {
          const data = await saveExisting(body)
          savedSnapshotAtRequest = canonicalizeSavedSnapshot(
            sentSnapshot,
            data.spec?.draft?.slug || data.spec?.slug
          )
          serverUpdatedAt.value =
            data.spec?.draft?.lastEditTime ||
            data.spec?.lastEditTime ||
            data.metadata.creationTimestamp ||
            new Date().toISOString()
          // 更新与发布是两个请求：先结算已经成功的 update，publish 失败也不能丢后续输入。
          hasNewerChanges = settleSavedSnapshot(savedSnapshotAtRequest, cacheName)
        }
        const { data: published } = await consoleApi.publishPost(editName.value)
        publishedPost = published
      } else {
        // 发布路径不做别名顺延：别名随创建即落定（冲突时服务端已 400），
        // 界面必须与真实发布别名一致，外链才正确
        const { data } = await consoleApi.createPost(body, true)
        publishedPost = data
        savedSnapshotAtRequest = canonicalizeSavedSnapshot(
          sentSnapshot,
          data.spec?.slug,
          data.spec?.title
        )
        const newName = data.metadata.name
        if (serializeForm() !== savedSnapshotAtRequest) {
          handleMoveCache(cacheName, newName)
        }
        editName.value = newName
        serverUpdatedAt.value =
          data.spec?.lastEditTime || data.metadata.creationTimestamp || new Date().toISOString()
      }
      syncSnapshotState(publishedPost)
      savedSnapshotAtRequest = canonicalizeSavedSnapshot(
        savedSnapshotAtRequest,
        publishedPost.spec?.slug
      )
      loadedPhase.value = 'PUBLISHED'
      publishedPermalink.value = `/bbs/post/${publishedPost.spec?.slug || formData.value.slug}`
      // 详情面板回填发布态元数据
      loadedMeta.value.publishTime =
        publishedPost.spec?.publishTime || loadedMeta.value.publishTime
      // publish 请求期间仍可能继续输入，发布成功后再以同一已发送快照复核一次。
      hasNewerChanges = settleSavedSnapshot(savedSnapshotAtRequest, cacheName)
      if (shouldCloseSetting) {
        // 顶部发布发出后才打开的设置弹窗，不属于本次请求，不能被旧响应关掉。
        settingSnapshot.value = ''
        settingVisible.value = false
      }
    })

    Toast.success('发布成功')
    if (hasNewerChanges) {
      // 发布请求后又有输入：留在编辑器，避免后续改动被静默丢弃
      await router.replace({ name: 'BbsPostEditor', query: { name: editName.value } })
    } else {
      await router.push({ name: 'BbsPosts' })
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

// Ctrl+S / ⌘S：与「保存」按钮同路径
useSaveKeybinding(() => {
  if (!editorReady.value || settingVisible.value) {
    return
  }
  if (needsCategoryGuard()) {
    openSetting()
    return
  }
  handleSave()
})

// 会话保活：长文编辑期间防 session 过期导致保存 401
useSessionKeepAlive()

function preview() {
  if (!editorReady.value) {
    return
  }
  if (loadedPhase.value !== 'PUBLISHED') {
    Toast.warning('未发布的帖子没有前台页，请先发布后再预览')
    return
  }
  if (publishedPermalink.value || formData.value.slug) {
    window.open(publishedPermalink.value || `/bbs/post/${formData.value.slug}`, '_blank')
  }
}

const settingSnapshot = ref('')

function openSetting() {
  if (!editorReady.value || saving.value) {
    return
  }
  settingSnapshot.value = JSON.stringify(formData.value)
  settingVisible.value = true
}

function closeSetting() {
  // 手动保存/发布已受理后，关闭弹窗只能隐藏 UI，不能把正等待队列保存的表单回滚掉。
  if (!saving.value && settingSnapshot.value) {
    formData.value = JSON.parse(settingSnapshot.value)
    // 快照一次性：关闭即失效——否则下次「缺分类直开弹窗」会把更晚写的正文
    // 回滚到这份陈旧快照上（静默丢正文）
  }
  settingSnapshot.value = ''
  settingVisible.value = false
}

function requestCloseSetting() {
  settingModal.value?.close()
}

/** 弹窗内按钮：先跑表单校验，通过后才真正保存 */
function requestSave(publish: boolean) {
  pendingPublish.value = publish
  settingForm.value?.submit()
}

function onSettingSubmit() {
  if (pendingPublish.value) {
    handlePublish()
  } else {
    handleSave()
  }
}

// 完整管理权限（管理员 / 全站版主）：无分类草稿对其可见可管，保存即保存
const canManage = utils.permission.has(['plugin:bbs:manage'])

/**
 * 分区版主的新建帖没有分类时，草稿不在其管辖内（列表不可见、打开会 403），
 * 必须先选板块；其余场景不设卡。
 */
function needsCategoryGuard() {
  return !editName.value && !formData.value.categoryName && !canManage
}

/**
 * 顶部「保存」：对齐官方编辑器与 UC——保存就是保存，默认值兜底（标题「未命名」、
 * 别名自动生成），分类可留到发布前再补，不弹设置弹窗。
 */
function onSaveClick() {
  if (needsCategoryGuard()) {
    openSetting()
    return
  }
  handleSave()
}

/**
 * 顶部「发布」：编辑已有直接保存并发布（对齐官方，无确认弹窗）；
 * 新建或缺分类（草稿阶段允许暂缺分类）先弹设置补全必填项。
 */
function onPublishClick() {
  if (editName.value && formData.value.categoryName) {
    handlePublish()
  } else {
    openSetting()
  }
}

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
    <VButton size="sm" @click="router.push({ name: 'BbsPosts' })">返回帖子列表</VButton>
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
          @click="router.push({ name: 'BbsPostSnapshots', query: { name: editName } })"
        >
          <template #icon><RiHistoryLine /></template>
          历史版本
        </VButton>
        <VButton v-if="editName" size="sm" :disabled="!editorReady" @click="preview">
          <template #icon><IconEye /></template>
          预览
        </VButton>
        <VButton size="sm" :disabled="!editorReady" :loading="saving" @click="onSaveClick">
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
          @click="onPublishClick"
        >
          <template #icon><IconSendPlaneFill /></template>
          发布
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
      :managed="true"
      :post-name="editName || undefined"
      @submit="onSettingSubmit"
    />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" @click="requestSave(true)">发布</VButton>
        <VButton :loading="saving" @click="requestSave(false)">保存</VButton>
        <VButton @click="requestCloseSetting">关闭</VButton>
      </VSpace>
    </template>
  </VModal>

</template>

<!-- bbs-editor-loading 见 styles/tokens.css -->
