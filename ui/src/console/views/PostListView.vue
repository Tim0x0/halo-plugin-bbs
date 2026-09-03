<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { refDebounced } from '@vueuse/core'
import { useRouter } from 'vue-router'
import { utils } from '@halo-dev/ui-shared'
import {
  VPageHeader,
  VButton,
  VCard,
  VSpace,
  VEmpty,
  VLoading,
  VModal,
  VPagination,
  VEntityContainer,
  VEntity,
  VEntityField,
  VAvatar,
  VDropdown,
  VDropdownItem,
  VDropdownDivider,
  Toast,
  Dialog,
  IconAddCircle,
  IconRefreshLine,
} from '@halo-dev/components'
import RiMegaphoneLine from '~icons/ri/megaphone-line'
import RiDeleteBinLine from '~icons/ri/delete-bin-line'
import { consoleApi } from '@/api/bbs'
import { useRouteQuery } from '@vueuse/router'
import { BBS_PHASE_LABELS, BBS_TYPE_LABELS } from '@/utils/post-labels'
import { formatTime, timeAgo } from '@/utils/date'
import {
  defaultPostForm,
  postFormFrom,
  type BbsPostVo,
  type CategoryVo,
} from '@/types/bbs'
import PostSettingModal from '@/console/components/PostSettingModal.vue'
import PostCommentListModal from '@/console/components/PostCommentListModal.vue'
import CategoryFilterDropdown from '@/console/components/CategoryFilterDropdown.vue'
import UserFilterDropdown from '@/console/components/UserFilterDropdown.vue'
import PostEntityStart from '@/shared/PostEntityStart.vue'
import PostStatusEnd from '@/shared/PostStatusEnd.vue'
import PostCommentsField from '@/shared/PostCommentsField.vue'
import PostLockField from '@/shared/PostLockField.vue'
import BbsCategorySelect from '@/shared/BbsCategorySelect.vue'
import PostModerationRecords from '@/shared/PostModerationRecords.vue'

/**
 * Console 帖子管理列表，对标官方文章列表：服务端筛选（状态/类型/分类/标题关键词/排序）、
 * 批量删除、行内快捷操作（置顶/发布切换）、设置弹窗仅改 spec 元数据。
 */
const router = useRouter()
const loading = ref(false)
const posts = ref<BbsPostVo[]>([])
const total = ref(0)
// 筛选与分页挂到 URL：刷新不丢、链接可分享、前进后退可用（对齐官方列表页）
// 逐键挂到 URL（对齐官方列表页）。useRouteQuery 内部对同一 tick 的多次写入做了
// 队列合并，「清空筛选」一次重置多项也只产生一次导航
const filters = reactive({
  page: useRouteQuery<number>('page', 1, { transform: Number }),
  size: useRouteQuery<number>('size', 20, { transform: Number }),
  keyword: useRouteQuery<string>('keyword', ''),
  phase: useRouteQuery<string | undefined>('phase'),
  type: useRouteQuery<string | undefined>('type'),
  category: useRouteQuery<string | undefined>('category'),
  sort: useRouteQuery<string | undefined>('sort'),
  owner: useRouteQuery<string | undefined>('owner'),
  // 回收站作为一种列表视图，同样挂 URL——刷新与分享链接都能停在回收站
  deleted: useRouteQuery<string | undefined>('deleted'),
})
/**
 * 回收站视图：列表只显示已删除的帖子，操作集也随之切换。
 *
 * <b>必须定义在下方 {@code watch(..., immediate)} 触发 {@code fetchPosts} 之前</b>——
 * fetchPosts 会读取 {@code inRecycleBin.value}，若它尚未初始化（暂时性死区），首次自动
 * 加载会抛 ReferenceError 并被静默吞掉，表现就是「首屏空列表、手动刷新才有数据」。
 */
const inRecycleBin = computed({
  get: () => filters.deleted === 'true',
  set: (value: boolean) => {
    filters.deleted = value ? 'true' : undefined
  },
})
const categories = ref<CategoryVo[]>([])
const selected = ref<string[]>([])

// 快捷设置弹窗
const settingModalVisible = ref(false)
const settingPost = ref<BbsPostVo | null>(null)
const settingForm = ref(defaultPostForm())
const settingSaving = ref(false)
const settingPublishing = ref(false)
/** 审核记录弹窗的目标帖名；审核留痕的唯一入口是列表行下拉 */
const moderationName = ref('')
/** 评论管理弹窗的目标帖名；评论列（有未审核时上色）点击打开 */
const commentsPost = ref('')

const phaseItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(BBS_PHASE_LABELS).map(([value, label]) => ({ label, value })),
])

const typeItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(BBS_TYPE_LABELS).map(([value, label]) => ({ label, value })),
])

// 排序白名单键与后端 CONSOLE_SORTS 一致
const sortItems = [
  { label: '创建时间 降序', value: undefined },
  { label: '创建时间 升序', value: 'creationTimestamp,asc' },
  { label: '发布时间 降序', value: 'publishTime,desc' },
  { label: '发布时间 升序', value: 'publishTime,asc' },
  { label: '最后活跃 降序', value: 'lastActivityTime,desc' },
  { label: '最后活跃 升序', value: 'lastActivityTime,asc' },
  { label: '最后编辑 降序', value: 'lastEditTime,desc' },
  { label: '最后编辑 升序', value: 'lastEditTime,asc' },
  { label: '评论数 降序', value: 'commentsCount,desc' },
  { label: '评论数 升序', value: 'commentsCount,asc' },
]

// 作者筛选需 system:users:view 权限（对齐官方对 UserFilterDropdown 的权限包裹）；
// 候选由组件自身带搜索地拉取
const canViewUsers = utils.permission.has(['system:users:view'])

const hasFilters = computed(
  () =>
    !!filters.phase ||
    !!filters.type ||
    !!filters.category ||
    !!filters.sort ||
    !!filters.owner ||
    !!filters.keyword
)

const isAllSelected = computed(
  () => posts.value.length > 0 && posts.value.every((p) => selected.value.includes(p.name))
)

// 帖子管理操作（审核/置顶/锁定/已解决/删除）对版主开放；分类管理仍需完整管理权限
const canModerate = utils.permission.has(['plugin:bbs:moderate'])
const canManage = utils.permission.has(['plugin:bbs:manage'])

/**
 * 无可见板块：分类列表也按版主管辖过滤，取不到任何分类说明
 * 要么站点还没建分类（管理员场景），要么这个版主还没被指派板块。
 * 帖子必须归属分类，所以此时列表必然为空——空状态要说清原因，
 * 否则分区版主进来只看到「暂无帖子」，完全不知道该找谁。
 *
 * 必须等加载成功才判定：接口失败时 categories 同样为空，
 * 直接判会把一次网络抖动说成「你没有权限」。
 */
const categoriesLoaded = ref(false)
const noCategories = computed(() => categoriesLoaded.value && categories.value.length === 0)

async function fetchCategories() {
  try {
    const { data } = await consoleApi.listCategories()
    categories.value = data || []
    categoriesLoaded.value = true
  } catch (error) {
    // 筛选项加载失败不阻塞列表；但把错误打到控制台——空 catch 会把同步异常（如引用
    // 未初始化变量）一并吞掉且无任何痕迹。
    console.error('[bbs] 分类筛选加载失败', error)
  }
}

const keywordDebounced = refDebounced(
  computed(() => filters.keyword),
  300
)
let fetchSeq = 0

async function fetchPosts() {
  const seq = ++fetchSeq
  loading.value = true
  selected.value = []
  try {
    const { data } = await consoleApi.listPosts({
      page: filters.page,
      size: filters.size,
      keyword: (keywordDebounced.value || '').trim() || undefined,
      categoryName: filters.category,
      type: filters.type,
      phase: filters.phase,
      sort: filters.sort,
      owner: filters.owner,
      deleted: inRecycleBin.value || undefined,
    })
    if (seq !== fetchSeq) {
      return
    }
    posts.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    // HTTP 错误由全局拦截器提示；这里再把错误打到控制台——空 catch 会把拼参数时的
    // 同步异常（如引用未初始化变量）一并吞掉且无任何痕迹。
    console.error('[bbs] 帖子列表加载失败', error)
  } finally {
    if (seq === fetchSeq) {
      loading.value = false
    }
  }
}

function resetFilters() {
  filters.phase = undefined
  filters.type = undefined
  filters.category = undefined
  filters.sort = undefined
  filters.owner = undefined
  filters.keyword = ''
}

// 筛选条件变化时回到第 1 页；随后的统一 watch 只发一次请求
watch(
  () => [
    keywordDebounced.value,
    filters.phase,
    filters.type,
    filters.category,
    filters.sort,
    filters.owner,
    filters.deleted,
  ],
  () => {
    filters.page = 1
  }
)

// 唯一的请求入口：分页与筛选都走这里。VPagination 不单独绑 @change，
// 否则翻页会触发两次请求。keyword 听 debounce 后的值，避免每个字符打一次。
watch(
  () => [
    filters.page,
    filters.size,
    keywordDebounced.value,
    filters.phase,
    filters.type,
    filters.category,
    filters.sort,
    filters.owner,
    filters.deleted,
  ],
  fetchPosts,
  { immediate: true }
)

function toggleSelect(name: string) {
  selected.value = selected.value.includes(name)
    ? selected.value.filter((n) => n !== name)
    : [...selected.value, name]
}

function toggleSelectAll() {
  selected.value = isAllSelected.value ? [] : posts.value.map((p) => p.name)
}

/** 行内状态切换操作表（操作名 → 端点）；新增操作只需补表。 */
const POST_ACTIONS = {
  publish: consoleApi.publishPost,
  unpublish: consoleApi.unpublishPost,
  approve: consoleApi.approvePost,
  withdraw: consoleApi.withdrawPost,
  pin: consoleApi.pinPost,
  unpin: consoleApi.unpinPost,
  lock: consoleApi.lockPost,
  unlock: consoleApi.unlockPost,
  solve: consoleApi.solvePost,
  unsolve: consoleApi.unsolvePost,
} satisfies Record<string, (name: string) => Promise<unknown>>

async function doAction(name: string, op: keyof typeof POST_ACTIONS, okText: string) {
  try {
    await POST_ACTIONS[op](name)
    Toast.success(okText)
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

/**
 * 列表内直接切换锁定（对齐官方「可见性」列的一键切换）。
 * 锁定是重操作——禁回复 + 作者不能再编辑或删除，故加二次确认；
 * 解锁是恢复性操作，不拦。
 */
function toggleLock(post: BbsPostVo) {
  if (post.locked) {
    doAction(post.name, 'unlock', '已解锁')
    return
  }
  Dialog.warning({
    title: '锁定帖子',
    description: `将锁定「${post.title}」：禁止回复，作者也不能再编辑或删除。`,
    confirmType: 'danger',
    onConfirm: () => doAction(post.name, 'lock', '已锁定'),
  })
}

/** 批量操作统一反馈：allSettled 统计成功/失败，部分失败也如实提示。 */
function reportBatch(results: PromiseSettledResult<unknown>[], okText: string) {
  const failed = results.filter((r) => r.status === 'rejected').length
  if (failed === 0) {
    Toast.success(`${okText}（${results.length} 项）`)
  } else {
    Toast.warning(`${okText} ${results.length - failed} 项，失败 ${failed} 项`)
  }
}

/**
 * 分片串行执行（对齐官方 chunk(names, 5)）：选中上百条时一次性并发会打爆服务端，
 * 每片 5 个、片间串行。
 */
async function runInChunks<T>(
  items: T[],
  task: (item: T) => Promise<unknown>,
  size = 5
): Promise<PromiseSettledResult<unknown>[]> {
  const results: PromiseSettledResult<unknown>[] = []
  for (let i = 0; i < items.length; i += size) {
    results.push(...(await Promise.allSettled(items.slice(i, i + size).map(task))))
  }
  return results
}

/**
 * 批量执行入口：统一「二次确认 → 分片串行 → 汇总反馈 → 刷新」四步。
 * 对齐 Discourse——每个批量操作都先确认，因为影响面是成批的。
 */
function runBatch(options: {
  title: string
  description: string
  okText: string
  danger?: boolean
  names?: string[]
  task: (name: string) => Promise<unknown>
}) {
  const names = options.names ?? selected.value
  if (names.length === 0) {
    return
  }
  const confirm = options.danger ? Dialog.warning : Dialog.info
  confirm({
    title: options.title,
    description: options.description,
    ...(options.danger ? { confirmType: 'danger' as const } : {}),
    onConfirm: async () => {
      const results = await runInChunks(names, options.task)
      reportBatch(results, options.okText)
      await fetchPosts()
    },
  })
}

/** 选中集合的状态摘要：决定哪些批量操作有意义、该不该出现（对齐 Discourse 的 visible 条件） */
const selectedPosts = computed(() => posts.value.filter((p) => selected.value.includes(p.name)))
const isPendingReview = (post: BbsPostVo) =>
  post.phase === 'PENDING' || post.draftPhase === 'PENDING'
const selectionHas = computed(() => {
  const list = selectedPosts.value
  return {
    pending: list.some(isPendingReview),
    draft: list.some((p) => p.phase === 'DRAFT' || p.phase === 'REJECTED'),
    published: list.some((p) => p.phase === 'PUBLISHED'),
    locked: list.some((p) => p.locked),
    unlocked: list.some((p) => !p.locked),
    pinned: list.some((p) => p.pinned),
    unpinned: list.some((p) => !p.pinned),
  }
})

const batchCount = computed(() => selected.value.length)

function batchApprove() {
  const names = selectedPosts.value.filter(isPendingReview).map((p) => p.name)
  runBatch({
    title: '批量通过审核',
    description: `将通过选中的 ${names.length} 篇待审核内容；首次投稿会发布，已发布帖的修改稿会替换当前前台版本。`,
    okText: '已通过并发布',
    names,
    task: (name) => consoleApi.approvePost(name),
  })
}

function batchPublish() {
  const names = selectedPosts.value
    .filter((p) => p.phase === 'DRAFT' || p.phase === 'REJECTED')
    .map((p) => p.name)
  runBatch({
    title: '批量发布',
    description: `将发布选中的 ${names.length} 篇未发布 / 已驳回帖子，发布后前台立即可见。`,
    okText: '已发布',
    names,
    task: (name) => consoleApi.publishPost(name),
  })
}

function batchUnpublish() {
  const names = selectedPosts.value.filter((p) => p.phase === 'PUBLISHED').map((p) => p.name)
  runBatch({
    title: '批量取消发布',
    description: `将取消发布选中的 ${names.length} 篇帖子，前台将不再展示。`,
    okText: '已取消发布',
    danger: true,
    names,
    task: (name) => consoleApi.unpublishPost(name),
  })
}

function batchLock(locked: boolean) {
  runBatch({
    title: locked ? '批量锁定' : '批量解锁',
    description: locked
      ? `将锁定选中的 ${batchCount.value} 篇帖子：禁止评论，作者也不能再编辑或删除。`
      : `将解锁选中的 ${batchCount.value} 篇帖子，恢复评论与编辑。`,
    okText: locked ? '已锁定' : '已解锁',
    danger: locked,
    task: (name) => (locked ? consoleApi.lockPost(name) : consoleApi.unlockPost(name)),
  })
}

function batchPin(pinned: boolean) {
  runBatch({
    title: pinned ? '批量置顶' : '批量取消置顶',
    description: `将对选中的 ${batchCount.value} 篇帖子${pinned ? '置顶' : '取消置顶'}。`,
    okText: pinned ? '已置顶' : '已取消置顶',
    task: (name) => (pinned ? consoleApi.pinPost(name) : consoleApi.unpinPost(name)),
  })
}

function batchRestore() {
  runBatch({
    title: '批量恢复',
    description: `将把选中的 ${batchCount.value} 篇帖子移出回收站。`,
    okText: '已恢复',
    task: (name) => consoleApi.restorePost(name),
  })
}

// 驳回弹窗（可附驳回原因，展示给作者）
const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectingPost = ref<BbsPostVo | null>(null)
const rejectSaving = ref(false)

function openReject(post: BbsPostVo) {
  rejectingPost.value = post
  rejectReason.value = ''
  rejectVisible.value = true
}

/** 批量驳回：rejectingPost 置空表示批量模式，选中的帖子共用同一条驳回原因 */
function openBatchReject() {
  rejectingPost.value = null
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  const reason = rejectReason.value.trim()
  rejectSaving.value = true
  try {
    if (rejectingPost.value) {
      await consoleApi.rejectPost(rejectingPost.value.name, reason)
      Toast.success('已驳回')
    } else {
      const names = selectedPosts.value.filter(isPendingReview).map((p) => p.name)
      const results = await runInChunks(names, (name) => consoleApi.rejectPost(name, reason))
      reportBatch(results, '已驳回')
    }
    rejectVisible.value = false
    await fetchPosts()
  } finally {
    rejectSaving.value = false
  }
}

// 批量设置分类
const batchCategoryVisible = ref(false)
const batchCategoryName = ref('')
const batchSaving = ref(false)

async function batchSetCategory() {
  batchSaving.value = true
  try {
    if (!batchCategoryName.value) {
      Toast.warning('请选择分类')
      return
    }
    const results = await runInChunks(selectedPosts.value, (post) =>
      consoleApi.updatePost(post.name, {
        title: post.title,
        slug: post.slug,
        type: post.type,
        categoryName: batchCategoryName.value,
        autoExcerpt: post.autoExcerpt,
        excerpt: post.excerpt,
        pinned: post.pinned,
        pinPriority: post.pinPriority,
      })
    )
    reportBatch(results, '已设置分类')
    batchCategoryVisible.value = false
    await fetchPosts()
  } finally {
    batchSaving.value = false
  }
}

function batchDelete() {
  if (inRecycleBin.value) {
    runBatch({
      title: '彻底删除',
      description: `将永久删除选中的 ${batchCount.value} 篇帖子，该操作不可恢复。`,
      okText: '已彻底删除',
      danger: true,
      task: (name) => consoleApi.deletePostPermanently(name),
    })
    return
  }
  runBatch({
    title: '移入回收站',
    description: `将把选中的 ${batchCount.value} 篇帖子移入回收站，前台立即不可见，之后可恢复。`,
    okText: '已移入回收站',
    danger: true,
    task: (name) => consoleApi.deletePost(name),
  })
}

function onDelete(post: BbsPostVo) {
  const permanent = inRecycleBin.value
  Dialog.warning({
    title: permanent ? '彻底删除' : '移入回收站',
    description: permanent
      ? `将永久删除「${post.title}」，该操作不可恢复。`
      : `将把「${post.title}」移入回收站，前台立即不可见，之后可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        if (permanent) {
          await consoleApi.deletePostPermanently(post.name)
        } else {
          await consoleApi.deletePost(post.name)
        }
        Toast.success(permanent ? '已彻底删除' : '已移入回收站')
        await fetchPosts()
      } catch {
        /* 请求错误由全局拦截器提示 */
      }
    },
  })
}

/** 单条恢复（回收站视图） */
async function onRestore(post: BbsPostVo) {
  try {
    await consoleApi.restorePost(post.name)
    Toast.success('已恢复')
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

function openSetting(post: BbsPostVo) {
  settingPost.value = post
  settingForm.value = postFormFrom(post)
  settingModalVisible.value = true
}

async function persistSetting() {
  const f = settingForm.value
  const post = settingPost.value
  if (!post) {
    return false
  }
  await consoleApi.updatePost(post.name, {
    title: f.title.trim(),
    slug: f.slug.trim(),
    type: f.type,
    categoryName: f.categoryName,
    autoExcerpt: f.autoExcerpt,
    excerpt: f.excerpt,
    pinned: f.pinned,
    pinPriority: f.pinPriority,
  })
  return true
}

async function saveSetting() {
  settingSaving.value = true
  try {
    // 走 PUT：正文不传，服务层保留原内容，并跑分类存在 / slug 唯一 / 改出问答清 solved
    await persistSetting()
    Toast.success('保存成功')
    settingModalVisible.value = false
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示（如别名冲突） */
  } finally {
    settingSaving.value = false
  }
}

/**
 * 设置弹窗主操作按帖子状态切换（与行内按钮同一口径）：
 * 进过审核流程（待审核 / 已驳回）一律「通过」；纯草稿才是「发布」。
 */
const settingPrimaryAction = computed<'approve' | 'publish' | undefined>(() => {
  const post = settingPost.value
  if (!post || post.phase === 'PUBLISHED') {
    return undefined
  }
  if (isPendingReview(post) || post.phase === 'REJECTED') {
    return 'approve'
  }
  return 'publish'
})

async function primaryFromSetting() {
  const post = settingPost.value
  const action = settingPrimaryAction.value
  if (!post || !action) {
    return
  }
  settingPublishing.value = true
  try {
    await persistSetting()
    if (action === 'approve') {
      await consoleApi.approvePost(post.name)
      Toast.success('已通过并发布')
    } else {
      await consoleApi.publishPost(post.name)
      Toast.success('已发布')
    }
    settingModalVisible.value = false
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示（缺分类 / 别名冲突等） */
  } finally {
    settingPublishing.value = false
  }
}

function toEditor(name?: string) {
  router.push({ name: 'BbsPostEditor', query: name ? { name } : {} })
}

onMounted(() => {
  fetchCategories()
})
</script>

<template>
  <!-- 回收站是同页的一种模式：标题 / 图标 / 按钮文案随之切换，
       让页头反映当前上下文（对齐官方回收站页的做法） -->
  <VPageHeader :title="inRecycleBin ? '帖子回收站' : '帖子'">
    <template #icon>
      <RiDeleteBinLine v-if="inRecycleBin" class="bbs-header-icon" />
      <RiMegaphoneLine v-else class="bbs-header-icon" />
    </template>
    <template #actions>
      <!-- 「返回」是退出动作，用默认色，与分类管理页的返回按钮保持一致（文案同样统一为
           「返回」——分类页本身也是个列表，写「返回列表」反而有歧义）；
           强调色留给「写帖子」这类主动作 -->
      <VButton
        v-permission="['plugin:bbs:moderate']"
        size="sm"
        @click="inRecycleBin = !inRecycleBin"
      >
        {{ inRecycleBin ? '返回' : '回收站' }}
      </VButton>
      <VButton v-permission="['plugin:bbs:manage']" size="sm" :route="{ name: 'BbsCategories' }">
        分类管理
      </VButton>
      <VButton v-permission="['plugin:bbs:moderate']" type="secondary" @click="toEditor()">
        <template #icon><IconAddCircle /></template>
        写帖子
      </VButton>
    </template>
  </VPageHeader>

  <div class="bbs-page-body">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="list-toolbar">
          <div class="list-toolbar__check">
            <input
              v-permission="['plugin:bbs:moderate']"
              type="checkbox"
              class="bbs-checkbox"
              :checked="isAllSelected"
              @change="toggleSelectAll"
            />
          </div>
          <div class="list-toolbar__main">
            <SearchInput v-if="!selected.length" v-model="filters.keyword" placeholder="搜索标题" />
            <!--
              批量操作按 Discourse 口径：高频的直接放外面，其余收进「更多」；
              每项按选中集合的实际状态显示——选中项里没有已锁定的帖子，就不出现「解锁」
            -->
            <VSpace v-else>
              <template v-if="inRecycleBin">
                <VButton @click="batchRestore">恢复</VButton>
                <!-- 彻底删除不可逆，仅完整管理角色可见（版主只能恢复） -->
                <VButton
                  v-permission="['plugin:bbs:manage']"
                  type="danger"
                  @click="batchDelete"
                >
                  彻底删除
                </VButton>
              </template>
              <template v-else>
                <VButton v-if="selectionHas.pending" type="secondary" @click="batchApprove">
                  通过审核
                </VButton>
                <VButton v-if="selectionHas.pending" type="danger" @click="openBatchReject">
                  驳回
                </VButton>
                <VButton type="danger" @click="batchDelete">删除</VButton>
                <VDropdown>
                  <VButton>更多</VButton>
                  <template #popper>
                    <VDropdownItem v-if="selectionHas.draft" @click="batchPublish">
                      发布
                    </VDropdownItem>
                    <VDropdownItem v-if="selectionHas.published" type="danger" @click="batchUnpublish">
                      取消发布
                    </VDropdownItem>
                    <VDropdownItem @click="batchCategoryVisible = true">设置分类</VDropdownItem>
                    <VDropdownDivider />
                    <VDropdownItem v-if="selectionHas.unlocked" @click="batchLock(true)">
                      锁定
                    </VDropdownItem>
                    <VDropdownItem v-if="selectionHas.locked" @click="batchLock(false)">
                      解锁
                    </VDropdownItem>
                    <VDropdownItem v-if="selectionHas.unpinned" @click="batchPin(true)">
                      置顶
                    </VDropdownItem>
                    <VDropdownItem v-if="selectionHas.pinned" @click="batchPin(false)">
                      取消置顶
                    </VDropdownItem>
                  </template>
                </VDropdown>
              </template>
            </VSpace>
          </div>
          <VSpace spacing="lg" class="list-toolbar__filters">
            <FilterCleanButton v-if="hasFilters" @click="resetFilters" />
            <FilterDropdown v-model="filters.phase" label="状态" :items="phaseItems" />
            <FilterDropdown v-model="filters.type" label="类型" :items="typeItems" />
            <CategoryFilterDropdown
              v-model="filters.category"
              label="分类"
              :categories="categories"
            />
            <UserFilterDropdown v-if="canViewUsers" v-model="filters.owner" label="作者" />
            <FilterDropdown v-model="filters.sort" label="排序" :items="sortItems" />
            <div v-tooltip="'刷新'" class="bbs-refresh-btn" @click="fetchPosts">
              <IconRefreshLine
                class="bbs-refresh-btn__icon"
                :class="{ 'bbs-refresh-btn__icon--spin': loading }"
              />
            </div>
          </VSpace>
        </div>
      </template>

      <VLoading v-if="loading" />
      <Transition v-else-if="posts.length === 0" appear name="fade">
        <!-- 三种空态按「用户该做什么」分：没板块可管 → 找管理员或去建分类；
             回收站空 → 说明回收站语义；其余 → 常规无内容 -->
        <VEmpty
          v-if="noCategories && !inRecycleBin"
          :title="canManage ? '还没有分类' : '暂无可管理的板块'"
          :message="
            canManage
              ? '帖子必须归属分类，先创建一个分类再发帖'
              : '你的账号还没有被指派板块。请联系管理员在分类设置中，把你的角色加入「本板块版主角色」'
          "
        >
          <template #actions>
            <VSpace>
              <VButton @click="fetchCategories">刷新</VButton>
              <VButton
                v-permission="['plugin:bbs:manage']"
                type="secondary"
                :route="{ name: 'BbsCategories' }"
              >
                <template #icon><IconAddCircle /></template>
                管理分类
              </VButton>
            </VSpace>
          </template>
        </VEmpty>
        <VEmpty
          v-else
          :title="inRecycleBin ? '回收站是空的' : '暂无帖子'"
          :message="
            inRecycleBin
              ? '被删除的帖子会先进入这里，可以恢复或彻底删除'
              : '当前筛选下没有内容，可以刷新或发布一篇新帖子 / 公告'
          "
        >
          <template #actions>
            <VSpace>
              <VButton @click="fetchPosts">刷新</VButton>
              <VButton
                v-if="!inRecycleBin"
                v-permission="['plugin:bbs:moderate']"
                type="secondary"
                @click="toEditor()"
              >
                <template #icon><IconAddCircle /></template>
                写帖子
              </VButton>
            </VSpace>
          </template>
        </VEmpty>
      </Transition>
      <Transition v-else appear name="fade">
        <VEntityContainer>
          <VEntity
            v-for="post in posts"
            :key="post.name"
            :is-selected="selected.includes(post.name)"
          >
            <template #checkbox>
              <input
                v-permission="['plugin:bbs:moderate']"
                type="checkbox"
                class="bbs-checkbox"
                :checked="selected.includes(post.name)"
                @change="toggleSelect(post.name)"
              />
            </template>
            <template #start>
              <PostEntityStart
                :post="post"
                :title-route="
                  canModerate && !inRecycleBin
                    ? { name: 'BbsPostEditor', query: { name: post.name } }
                    : undefined
                "
              />
            </template>
            <template #end>
              <!-- 官方口径总数 + 待审核上色；可点（回收站与无审核权限时只读） -->
              <PostCommentsField
                :count="post.totalCommentCount"
                :pending="post.pendingCommentCount"
                :clickable="canModerate && !inRecycleBin"
                @open="commentsPost = post.name"
              />
              <PostLockField
                :post="post"
                :readonly="!canModerate || inRecycleBin"
                @toggle-lock="toggleLock(post)"
              />
              <VEntityField width="2rem">
                <template #description>
                  <VAvatar
                    v-if="post.owner"
                    v-tooltip="post.owner.displayName"
                    :src="post.owner.avatar"
                    :alt="post.owner.displayName"
                    size="xs"
                    circle
                  />
                </template>
              </VEntityField>
              <PostStatusEnd :post="post" />
              <VEntityField width="7rem">
                <template #description>
                  <span
                    v-tooltip="formatTime(post.publishTime || post.creationTimestamp)"
                    class="bbs-entity-time"
                  >
                    {{ timeAgo(post.publishTime || post.creationTimestamp) }}
                  </span>
                </template>
              </VEntityField>
            </template>
            <template v-if="canModerate" #dropdownItems>
              <!-- 回收站里只保留恢复 / 彻底删除，其余操作对已删除的帖子没有意义 -->
              <template v-if="inRecycleBin">
                <VDropdownItem @click="onRestore(post)">恢复</VDropdownItem>
                <!-- 彻底删除不可逆，仅完整管理角色可见（分隔线随之隐藏，免留孤线） -->
                <VDropdownDivider v-permission="['plugin:bbs:manage']" />
                <VDropdownItem
                  v-permission="['plugin:bbs:manage']"
                  type="danger"
                  @click="onDelete(post)"
                >
                  彻底删除
                </VDropdownItem>
              </template>
              <template v-else>
              <VDropdownItem @click="toEditor(post.name)">编辑</VDropdownItem>
              <VDropdownItem @click="openSetting(post)">设置</VDropdownItem>
              <VDropdownItem @click="moderationName = post.name">审核记录</VDropdownItem>
              <VDropdownDivider />
              <template v-if="isPendingReview(post)">
                <VDropdownItem @click="doAction(post.name, 'approve', '已通过并发布')">
                  通过审核
                </VDropdownItem>
                <VDropdownItem type="danger" @click="openReject(post)">驳回</VDropdownItem>
                <VDropdownItem @click="doAction(post.name, 'withdraw', '已撤回，回到草稿')">
                  取消提交
                </VDropdownItem>
              </template>
              <template v-else-if="post.phase === 'PUBLISHED'">
                <!-- 被驳回的修改稿走「通过」（推翻驳回直接发布）；普通修改稿走「发布」 -->
                <VDropdownItem
                  v-if="post.draftPhase === 'REJECTED'"
                  @click="doAction(post.name, 'approve', '已通过并发布')"
                >
                  通过
                </VDropdownItem>
                <VDropdownItem
                  v-else-if="post.hasDraft"
                  @click="doAction(post.name, 'publish', '已发布')"
                >
                  发布
                </VDropdownItem>
                <VDropdownItem type="danger" @click="doAction(post.name, 'unpublish', '已取消发布')">
                  取消发布
                </VDropdownItem>
              </template>
              <!-- 未发布：已驳回走「通过」，纯草稿走「发布」；无分类先进设置补齐 -->
              <VDropdownItem
                v-else
                @click="
                  post.category
                    ? doAction(
                        post.name,
                        post.phase === 'REJECTED' ? 'approve' : 'publish',
                        post.phase === 'REJECTED' ? '已通过并发布' : '已发布'
                      )
                    : openSetting(post)
                "
              >
                {{ post.phase === 'REJECTED' ? '通过' : '发布' }}
              </VDropdownItem>
              <VDropdownItem
                v-if="post.pinned"
                @click="doAction(post.name, 'unpin', '已取消置顶')"
              >
                取消置顶
              </VDropdownItem>
              <VDropdownItem v-else @click="doAction(post.name, 'pin', '已置顶')">
                置顶
              </VDropdownItem>
              <VDropdownItem
                v-if="post.locked"
                @click="doAction(post.name, 'unlock', '已解锁')"
              >
                解锁
              </VDropdownItem>
              <VDropdownItem v-else @click="toggleLock(post)">锁定</VDropdownItem>
              <template v-if="post.type === 'QUESTION'">
                <VDropdownItem
                  v-if="post.solved"
                  @click="doAction(post.name, 'unsolve', '已取消已解决标记')"
                >
                  取消已解决
                </VDropdownItem>
                <VDropdownItem v-else @click="doAction(post.name, 'solve', '已标记为已解决')">
                  标记已解决
                </VDropdownItem>
              </template>
              <VDropdownDivider />
              <VDropdownItem type="danger" @click="onDelete(post)">删除</VDropdownItem>
              </template>
            </template>
          </VEntity>
        </VEntityContainer>
      </Transition>

      <template #footer>
        <VPagination
          v-model:page="filters.page"
          v-model:size="filters.size"
          :total="total"
          :size-options="[20, 30, 50, 100]"
        />
      </template>
    </VCard>
  </div>

  <PostSettingModal
    v-if="settingModalVisible"
    v-model="settingForm"
    :categories="categories"
    :post-name="settingPost?.name"
    :saving="settingSaving"
    :publishing="settingPublishing"
    :primary-action="settingPrimaryAction"
    :primary-label="settingPrimaryAction === 'approve' ? '通过' : '发布'"
    @confirm="saveSetting"
    @primary="primaryFromSetting"
    @close="settingModalVisible = false"
  />

  <VModal
    v-if="rejectVisible"
    title="驳回帖子"
    :width="500"
    mount-to-body
    @close="rejectVisible = false"
  >
    <FormKit
      v-model="rejectReason"
      type="textarea"
      label="驳回原因"
      :rows="3"
      :help="
        rejectingPost
          ? `将展示给「${rejectingPost.title}」的作者，可留空`
          : `将展示给选中的 ${batchCount} 篇帖子的作者，可留空`
      "
    />
    <template #footer>
      <VSpace>
        <VButton type="danger" :loading="rejectSaving" @click="confirmReject">驳回</VButton>
        <VButton @click="rejectVisible = false">取消</VButton>
      </VSpace>
    </template>
  </VModal>

  <VModal
    v-if="batchCategoryVisible"
    title="批量设置分类"
    :width="500"
    mount-to-body
    @close="batchCategoryVisible = false"
  >
    <FormKit
      v-model="batchCategoryName"
      type="text"
      label="分类"
      :help="`将应用到选中的 ${selected.length} 篇帖子`"
    >
      <template #input="ctx">
        <BbsCategorySelect
          :model-value="String(ctx.value ?? '')"
          :categories="categories"
          placeholder="选择分类"
          @update:model-value="ctx.node.input($event)"
        />
      </template>
    </FormKit>
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="batchSaving" @click="batchSetCategory">确定</VButton>
        <VButton @click="batchCategoryVisible = false">取消</VButton>
      </VSpace>
    </template>
  </VModal>

  <!-- 审核记录：唯一入口在行下拉菜单（编辑器不挂此按钮） -->
  <PostModerationRecords
    v-if="moderationName"
    :post-name="moderationName"
    mode="console"
    @close="moderationName = ''"
  />

  <!-- 评论管理：评论列点击打开（对齐官方按主题查看评论的弹窗） -->
  <PostCommentListModal
    v-if="commentsPost"
    :post-name="commentsPost"
    @close="commentsPost = ''"
  />
</template>

<style scoped>
/* bbs-header-icon / bbs-page-body / bbs-refresh-btn / bbs-entity-time 见 styles/tokens.css */
.list-toolbar {
  display: flex;
  width: 100%;
  flex-direction: column;
  align-items: flex-start;
  gap: 1rem;
  background: var(--bbs-bg-soft);
  padding: 0.75rem 1rem;
}

@media (min-width: 640px) {
  .list-toolbar {
    flex-direction: row;
    align-items: center;
    flex-wrap: wrap;
  }
}

.list-toolbar__check {
  display: none;
}

@media (min-width: 640px) {
  .list-toolbar__check {
    display: flex;
    align-items: center;
  }
}

.list-toolbar__main {
  display: flex;
  width: 100%;
  flex: 1;
  align-items: center;
}

@media (min-width: 640px) {
  .list-toolbar__main {
    width: auto;
  }
}

.list-toolbar__filters {
  flex-wrap: wrap;
}

.bbs-checkbox {
  height: 1rem;
  width: 1rem;
  cursor: pointer;
}
</style>
