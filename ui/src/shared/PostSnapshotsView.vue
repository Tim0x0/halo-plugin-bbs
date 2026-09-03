<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLocalStorage } from '@vueuse/core'
import { useRouteQuery } from '@vueuse/router'
import {
  Dialog,
  IconHistoryLine,
  IconInformation,
  Toast,
  VButton,
  VCard,
  VDropdown,
  VLoading,
  VPageHeader,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { timeAgo } from '@/utils/date'
import DOMPurify from 'dompurify'
import { visualDomDiff } from 'visual-dom-diff'
import { consoleApi, ucApi } from '@/api/bbs'
import type { BbsPostVo, BbsSnapshotDto } from '@/types/bbs'

/**
 * 帖子历史版本页，布局与交互逐条对齐 Halo 官方 Console 的快照页
 * （ui/console-src/components/snapshots/BaseSnapshots.vue 四件套）：
 *
 * - 顶层路由全屏页（不挂根布局，无 Console 左侧菜单栏，官方同款）：
 *   VPageHeader（帖子标题 + 历史图标）+ 返回 / 对比模式开关 / 清理；
 * - 两栏卡片：左侧版本列表点击选中（无 checkbox），操作按钮 hover 浮现——
 *   恢复仅 !isHead、删除仅 !isBase，且已发布（release）快照整体隐藏操作，与官方一致；
 * - 选中态与对比模式存路由 query（snapshot-names / diff-mode），刷新与分享不丢；
 * - 对比模式是显式开关：开启后多选，选满 2 个再点第 3 个则重置为该版本，
 *   且始终按列表时间顺序排序；关闭时只保留第一个选择；未选满 2 个提示选择；
 * - 对比视图三栏（旧版本 / 新版本 / 差异）+「只看差异 / 同步滚动」开关
 *   （localStorage 记忆）；图例为差异栏标题旁的 hover 弹层（删除 / 新增 / 修改）；
 * - 单版本视图直接渲染正文（官方 SnapshotContent 形态，不加标题卡片）；
 * - 徽标三种：已发布（== release）/ 未发布（== head 且 head≠release）/ 基线（== base）；
 * - 删除中的版本（带 deletionTimestamp）由查询层 1000ms 轮询等待真正消失；
 * - 清理 = 前端循环逐个删（保留 base/release/head），后端无批量端点；按钮恒可点，
 *   无可清理项在确认后 Toast 提示（官方同款，不做禁用态）；
 * - 正文区套用官方 markdown-body 深样式（段落间距 / 代码块深色底 / 任务列表等）；
 * - 内容区 DOMPurify 净化；对比用 visualDomDiff(skipModified: true)，纯浏览器侧。
 *   方向：列表按创建时间降序，选中序列第 0 个是新版本、第 1 个是旧版本（官方同款）。
 *
 * Console 与 UC 由外层薄包装以 mode 区分，UC 走归属校验后的聚合 API。
 */
const props = defineProps<{
  mode: 'console' | 'uc'
}>()

const route = useRoute()
const router = useRouter()
const api = computed(() => (props.mode === 'console' ? consoleApi : ucApi))

const postName = computed(() => (route.query.name as string) || '')
const post = ref<BbsPostVo>()

const loading = ref(true)
const contentLoading = ref(false)
const snapshots = ref<BbsSnapshotDto[]>([])
/** 帖子不存在 / 无权查看（后端 404/403）：明确占位，不留白屏 */
const postMissing = ref(false)
let pollTimer: number | undefined
let contentRequestId = 0

const pointers = computed(() => ({
  base: post.value?.baseSnapshot,
  head: post.value?.headSnapshot,
  release: post.value?.releaseSnapshot,
}))

// —— 选中态与对比模式（官方存路由 query）——
const selectedQuery = useRouteQuery<string[]>('snapshot-names', [], {
  transform: (value) => (Array.isArray(value) ? value : value ? [value] : []),
})
const diffModeQuery = useRouteQuery<string | undefined>('diff-mode')
const diffMode = computed(() => diffModeQuery.value === 'true')

const selectedNames = computed(() => selectedQuery.value || [])
const selectedOne = computed(() =>
  selectedNames.value.length === 1 ? selectedNames.value[0] : ''
)
const canDiff = computed(
  () => diffMode.value && selectedNames.value.length === 2
)

const singleContent = ref('')
const oldContent = ref('')
const newContent = ref('')
const diffContent = ref('')

// 对比视图开关（官方同款，localStorage 记忆）
const onlyDiff = useLocalStorage('bbs-snapshot-diff-only-diff', false)
const enableSyncScroll = useLocalStorage('bbs-snapshot-diff-sync-scroll', true)

function isReleased(name: string) {
  return pointers.value.release === name
}

function isHead(name: string) {
  // 官方口径：head 与 release 相同时列表里不标「未发布」
  return (
    pointers.value.head !== pointers.value.release && pointers.value.head === name
  )
}

function isBase(name: string) {
  return pointers.value.base === name
}

async function loadPost() {
  const { data } =
    props.mode === 'console'
      ? await consoleApi.getPost(postName.value)
      : await ucApi.getMine(postName.value)
  post.value = data
}

async function fetchSnapshots(silent = false) {
  if (!postName.value) {
    return
  }
  if (!silent) {
    loading.value = true
  }
  try {
    const [, response] = await Promise.all([
      post.value ? Promise.resolve() : loadPost(),
      api.value.listSnapshots(postName.value),
    ])
    snapshots.value = response.data || []
  } catch (error) {
    // HTTP 错误由全局拦截器提示；这里再把错误打到控制台——空 catch 会把同步异常一并
    // 吞掉且无任何痕迹。
    console.error('[bbs] 快照列表加载失败', error)
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 404 || status === 403) {
      postMissing.value = true
    }
  } finally {
    loading.value = false
  }
  // 官方 refetchInterval：有删除中的快照时 1000ms 轮询等待真正消失
  window.clearTimeout(pollTimer)
  if (snapshots.value.some((item) => item.metadata.deletionTimestamp)) {
    pollTimer = window.setTimeout(() => fetchSnapshots(true), 1000)
  }
}

function handleToggleDiffMode() {
  // 官方：关闭对比模式时只保留第一个选择
  if (diffMode.value) {
    selectedQuery.value = [selectedNames.value[0]].filter(Boolean)
  }
  diffModeQuery.value = !diffMode.value ? 'true' : undefined
}

/** 官方 handleSelectSnapshot 的选择状态机：普通模式单选；对比模式增删/满 2 重置/按序排列。 */
function handleSelectSnapshot(name: string) {
  if (!diffMode.value) {
    selectedQuery.value = [name]
    return
  }
  if (selectedNames.value.includes(name)) {
    selectedQuery.value = selectedNames.value.filter((item) => item !== name)
    return
  }
  if (selectedNames.value.length === 2) {
    selectedQuery.value = [name]
    return
  }
  selectedQuery.value = [...selectedNames.value, name].sort((a, b) => {
    const indexOf = (value: string) =>
      snapshots.value.findIndex((item) => item.metadata.name === value)
    return indexOf(a) - indexOf(b)
  })
}

// 官方 watch：无选择时选第一个；选中的版本被删后重置为第一个
watch(
  () => snapshots.value,
  (value) => {
    if (!value.length) return
    if (!selectedNames.value.length) {
      selectedQuery.value = [value[0].metadata.name]
      return
    }
    if (!selectedNames.value.some((name) => value.some((item) => item.metadata.name === name))) {
      selectedQuery.value = [value[0].metadata.name]
    }
  },
  { immediate: true }
)

// —— 内容区 ——
watch(
  [selectedNames, diffMode],
  () => refreshContent(),
  { immediate: true }
)

async function refreshContent() {
  const requestId = ++contentRequestId
  singleContent.value = ''
  oldContent.value = ''
  newContent.value = ''
  diffContent.value = ''
  if (!postName.value || !selectedNames.value.length) return
  contentLoading.value = true
  try {
    if (!diffMode.value) {
      if (selectedNames.value.length !== 1) return
      const { data } = await api.value.getContent(postName.value, selectedNames.value[0])
      if (requestId === contentRequestId) {
        singleContent.value = DOMPurify.sanitize(data.content || '')
      }
      return
    }
    if (!canDiff.value) {
      // 未选满两个：提示由模板渲染，这里不发请求
      return
    }
    // 对比：两份全量都由服务端重建，diff 完全在浏览器侧做（官方同款）。
    // 方向：列表按创建时间降序，选中序列第 0 个是新版本、第 1 个是旧版本。
    const [newerName, olderName] = selectedNames.value
    const [newerRes, olderRes] = await Promise.all([
      api.value.getContent(postName.value, newerName),
      api.value.getContent(postName.value, olderName),
    ])
    if (requestId !== contentRequestId) return
    oldContent.value = DOMPurify.sanitize(olderRes.data.content || '')
    newContent.value = DOMPurify.sanitize(newerRes.data.content || '')
    const oldNode = document.createElement('div')
    oldNode.innerHTML = oldContent.value
    const newNode = document.createElement('div')
    newNode.innerHTML = newContent.value
    const diff = visualDomDiff(oldNode, newNode, { skipModified: true })
    const wrapper = document.createElement('div')
    wrapper.append(diff.cloneNode(true))
    // 输入与 diff 输出都消毒：库生成的结果同样不扩大信任边界（官方同款做法）
    diffContent.value = DOMPurify.sanitize(wrapper.innerHTML)
  } catch (error) {
    // HTTP 错误由全局拦截器提示；这里再把错误打到控制台——空 catch 会把同步异常一并
    // 吞掉且无任何痕迹。
    console.error('[bbs] 快照内容加载失败', error)
  } finally {
    if (requestId === contentRequestId) {
      contentLoading.value = false
    }
  }
}

// —— 对比三栏同步滚动（原生实现，按百分比互推；官方用 overlayscrollbars，行为一致）——
const oldPaneRef = ref<HTMLElement | null>(null)
const newPaneRef = ref<HTMLElement | null>(null)
const diffPaneRef = ref<HTMLElement | null>(null)
let isSyncing = false

function syncFrom(source: HTMLElement, targets: (HTMLElement | null)[]) {
  if (isSyncing || !enableSyncScroll.value || onlyDiff.value) {
    return
  }
  const sourceMax = source.scrollHeight - source.clientHeight
  if (sourceMax <= 0) {
    return
  }
  const ratio = source.scrollTop / sourceMax
  isSyncing = true
  for (const target of targets) {
    if (!target) {
      continue
    }
    const targetMax = target.scrollHeight - target.clientHeight
    if (targetMax <= 0) {
      continue
    }
    target.scrollTop = ratio * targetMax
  }
  // 闸口稍后放开，避免互推形成回环
  setTimeout(() => {
    isSyncing = false
  }, 10)
}

/** 任一窗格滚动时把其余窗格同步到同一比例位置。 */
function handlePaneScroll(source: HTMLElement | null) {
  if (!source) {
    return
  }
  const targets = [oldPaneRef.value, newPaneRef.value, diffPaneRef.value].filter(
    (pane) => pane !== source
  )
  syncFrom(source, targets)
}

function handleRestore(name: string) {
  Dialog.warning({
    title: '恢复此版本',
    description:
      '将以该版本的正文新建一个版本；未发布帖不会发布，已发布帖按审核策略重新发布或提交审核。旧历史不会被改写。',
    onConfirm: async () => {
      await api.value.revertContent(postName.value, name)
      await fetchSnapshots(true)
      Toast.success('恢复成功')
    },
  })
}

function handleDelete(name: string) {
  Dialog.warning({
    title: '删除版本',
    description: '该版本将被永久删除，无法恢复。',
    confirmType: 'danger',
    onConfirm: async () => {
      await api.value.deleteContent(postName.value, name)
      await fetchSnapshots(true)
      Toast.success('删除成功')
    },
  })
}

function handleCleanup() {
  Dialog.warning({
    title: '清理历史版本',
    description: '将永久删除除基线、已发布版本与当前工作版本外的全部历史版本。',
    confirmType: 'danger',
    onConfirm: async () => {
      const targets = snapshots.value
        .filter(
          (item) =>
            !isReleased(item.metadata.name) &&
            !isHead(item.metadata.name) &&
            !isBase(item.metadata.name)
        )
        .map((item) => item.metadata.name)
      if (!targets.length) {
        Toast.info('没有可清理的历史版本')
        return
      }
      for (const target of targets) {
        await api.value.deleteContent(postName.value, target)
      }
      await fetchSnapshots(true)
      Toast.success('清理成功')
    },
  })
}

onMounted(fetchSnapshots)
onBeforeUnmount(() => window.clearTimeout(pollTimer))
</script>

<template>
  <VPageHeader :title="post?.title || '历史版本'">
    <template #icon>
      <IconHistoryLine />
    </template>
    <template #actions>
      <VButton size="sm" ghost @click="router.back()">返回</VButton>
      <VButton size="sm" @click="handleToggleDiffMode">
        {{ diffMode ? '退出对比' : '对比' }}
      </VButton>
      <!-- 官方：恒可点，无可清理项在确认后 Toast 提示，不做禁用态 -->
      <VButton size="sm" type="danger" @click="handleCleanup">
        清理
      </VButton>
    </template>
  </VPageHeader>

  <div class="snapshots-page">
    <VCard :body-class="['snapshots-card-body']">
      <!-- 路径指向的帖子不存在 / 无权查看：明确占位，不留白屏 -->
      <div v-if="postMissing" class="snapshot-missing">
        <p>帖子不存在，或无权查看</p>
        <VButton size="sm" @click="router.back()">返回</VButton>
      </div>
      <div v-else class="snapshot-grid">
        <div class="snapshot-list">
          <VLoading v-if="loading" />
          <ul v-else role="list">
            <li
              v-for="snapshot in snapshots"
              :key="snapshot.metadata.name"
              @click="handleSelectSnapshot(snapshot.metadata.name)"
            >
              <div
                class="snapshot-item"
                :class="{ selected: selectedNames.includes(snapshot.metadata.name) }"
              >
                <div class="snapshot-item__head">
                  <span class="snapshot-item__time">
                    {{ timeAgo(snapshot.metadata.creationTimestamp) }}
                  </span>
                  <span class="snapshot-item__tags">
                    <VTag v-if="isReleased(snapshot.metadata.name)" theme="primary">
                      已发布
                    </VTag>
                    <VTag v-if="isHead(snapshot.metadata.name)">未发布</VTag>
                    <VTag v-if="isBase(snapshot.metadata.name)">基线</VTag>
                    <VStatusDot
                      v-if="snapshot.metadata.deletionTimestamp"
                      v-tooltip="'删除中'"
                      state="warning"
                      animate
                    />
                  </span>
                </div>
                <div class="snapshot-item__foot">
                  <span class="snapshot-item__owner">{{ snapshot.spec.owner || 'system' }}</span>
                  <!-- 官方口径：已发布（release）快照整体隐藏操作 -->
                  <span
                    v-if="!isReleased(snapshot.metadata.name)"
                    class="snapshot-item__ops"
                    @click.stop
                  >
                    <VButton
                      v-if="!isHead(snapshot.metadata.name)"
                      size="xs"
                      @click="handleRestore(snapshot.metadata.name)"
                    >
                      恢复
                    </VButton>
                    <VButton
                      v-if="!isBase(snapshot.metadata.name)"
                      size="xs"
                      type="danger"
                      @click="handleDelete(snapshot.metadata.name)"
                    >
                      删除
                    </VButton>
                  </span>
                </div>
              </div>
            </li>
          </ul>
        </div>

        <div class="snapshot-content">
          <VLoading v-if="contentLoading" />
          <template v-else-if="diffMode">
            <!-- 对比工具条：标题 + 只看差异 / 同步滚动（官方 SnapshotDiffContent 同款） -->
            <div class="diff-toolbar">
              <span class="diff-toolbar__title">版本对比</span>
              <FormKit
                v-model="onlyDiff"
                type="checkbox"
                label="只看差异"
                :classes="{ outer: '!py-0', wrapper: '!mb-0' }"
              />
              <FormKit
                v-model="enableSyncScroll"
                type="checkbox"
                label="同步滚动"
                :disabled="onlyDiff"
                :classes="{ outer: '!py-0', wrapper: '!mb-0' }"
              />
            </div>
            <div v-if="!canDiff" class="snapshot-content__empty">
              请选择两个版本进行对比
            </div>
            <div v-else class="diff-grid" :class="{ 'diff-grid--only': onlyDiff }">
              <div
                v-if="!onlyDiff"
                ref="oldPaneRef"
                class="diff-pane"
                @scroll="handlePaneScroll(oldPaneRef)"
              >
                <div class="diff-pane__heading">旧版本</div>
                <div class="snapshot-content__body" v-html="oldContent" />
              </div>
              <div
                v-if="!onlyDiff"
                ref="newPaneRef"
                class="diff-pane"
                @scroll="handlePaneScroll(newPaneRef)"
              >
                <div class="diff-pane__heading">新版本</div>
                <div class="snapshot-content__body" v-html="newContent" />
              </div>
              <div ref="diffPaneRef" class="diff-pane" @scroll="handlePaneScroll(diffPaneRef)">
                <div class="diff-pane__heading diff-pane__heading--legend">
                  <span>差异</span>
                  <VDropdown :triggers="['hover']">
                    <IconInformation class="diff-legend-info" />
                    <template #popper>
                      <ul class="diff-legend-list">
                        <li class="diff-legend-item diff-legend-item--removed">删除</li>
                        <li class="diff-legend-item diff-legend-item--added">新增</li>
                        <li class="diff-legend-item diff-legend-item--modified">修改</li>
                      </ul>
                    </template>
                  </VDropdown>
                </div>
                <div class="snapshot-content__body" v-html="diffContent" />
              </div>
            </div>
          </template>
          <!-- 单版本视图：官方直接渲染正文，不加标题卡片 -->
          <div
            v-else-if="selectedOne"
            class="snapshot-content__body snapshot-content__body--single"
            v-html="singleContent"
          />
          <div v-else class="snapshot-content__empty">请从左侧选择要查看的版本</div>
        </div>
      </div>
    </VCard>
  </div>
</template>

<style scoped>
/* 顶层路由全屏渲染（无布局边距）：对齐官方 m-0 md:m-4——
   移动端贴边，桌面端 1rem 外边距；卡片高 = 100vh − 页头(3.5rem) − 上下边距(2rem) */
.snapshots-page {
  margin: 0;
}

@media (min-width: 768px) {
  .snapshots-page {
    margin: 1rem;
  }
}

.snapshots-card-body {
  height: calc(100vh - 5.5rem);
  padding: 0 !important;
}

.snapshot-missing {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  color: var(--bbs-text-muted);
}

.snapshot-missing p {
  margin: 0;
}

.snapshot-grid {
  display: grid;
  grid-template-columns: minmax(220px, 25%) minmax(0, 1fr);
  height: 100%;
}

.snapshot-list {
  position: relative;
  height: 100%;
  overflow: auto;
  border-right: 1px solid var(--bbs-border);
}

.snapshot-list ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.snapshot-list li + li {
  border-top: 1px solid var(--bbs-border);
}

.snapshot-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem;
  cursor: pointer;
}

.snapshot-item:hover {
  background: var(--bbs-bg-soft);
}

.snapshot-item.selected {
  background: var(--bbs-bg-soft);
}

.snapshot-item.selected::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 2px;
  background: var(--bbs-accent);
}

.snapshot-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.snapshot-item__time {
  overflow: hidden;
  font-size: 0.875rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.snapshot-item.selected .snapshot-item__time {
  font-weight: 600;
}

.snapshot-item__tags {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 0.4rem;
}

.snapshot-item__foot {
  display: flex;
  height: 1.5rem;
  align-items: flex-end;
  justify-content: space-between;
  gap: 0.5rem;
}

.snapshot-item__owner {
  flex: 1;
  overflow: hidden;
  color: var(--bbs-text-muted);
  font-size: 0.75rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 官方：操作按钮 hover 浮现，未 hover 时隐藏避免列表噪音 */
.snapshot-item__ops {
  display: none;
  flex: none;
  gap: 0.5rem;
}

.snapshot-item:hover .snapshot-item__ops {
  display: inline-flex;
}

.snapshot-content {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.snapshot-content__empty {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  color: var(--bbs-text-muted);
  padding: 2rem;
}

/* —— 对比工具条与三栏（官方 SnapshotDiffContent 形态） —— */

.diff-toolbar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 1rem;
  border-bottom: 1px solid var(--bbs-border);
  padding: 0.75rem 1rem;
}

.diff-toolbar__title {
  margin-right: auto;
  font-weight: 600;
}

.diff-grid {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.diff-grid--only {
  grid-template-columns: minmax(0, 1fr);
}

.diff-pane {
  height: 100%;
  overflow: auto;
  border-right: 1px solid var(--bbs-border);
}

.diff-pane:last-child {
  border-right: 0;
}

.diff-pane__heading {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  border-bottom: 1px solid var(--bbs-border);
  background: var(--bbs-bg-surface);
  padding: 0.6rem 1rem;
  font-size: 0.85rem;
}

.diff-legend-info {
  width: 1rem;
  height: 1rem;
  color: var(--bbs-text-muted);
  cursor: pointer;
}

.diff-legend-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0.25rem;
  list-style: none;
  width: 8rem;
}

.diff-legend-item {
  border-radius: 0.25rem;
  padding: 0.15rem 0.4rem;
  font-size: 0.75rem;
}

.diff-legend-item--removed {
  background: #ffe6e6;
  text-decoration: line-through;
}

.diff-legend-item--added {
  background: #e6ffe6;
}

.diff-legend-item--modified {
  background: #e6f2ff;
}

.snapshot-content__body {
  overflow-wrap: anywhere;
  padding: 1rem;
}

.snapshot-content__body :deep(.vdd-removed) {
  background: #ffe6e6;
  text-decoration: line-through;
}

.snapshot-content__body :deep(.vdd-added) {
  background: #e6ffe6;
}

.snapshot-content__body :deep(.vdd-modified) {
  background: #e6f2ff;
}

/* 官方 markdown-body 正文样式：段落间距 / 代码块深色底 / 任务列表 / 列表符号还原 */

.snapshot-content__body :deep(p) {
  margin-bottom: 0;
  margin-top: 0.75em;
}

.snapshot-content__body :deep(pre) {
  background: #0d0d0d;
  margin: 0;
  padding: 0.75rem 1rem;
}

.snapshot-content__body :deep(pre code) {
  background: none;
  border-radius: 0;
  color: #ccc;
  font-size: 0.8rem;
  padding: 0 !important;
}

.snapshot-content__body :deep(ul[data-type='taskList']) {
  list-style: none;
  padding: 0;
}

.snapshot-content__body :deep(ul[data-type='taskList'] p) {
  margin: 0;
}

.snapshot-content__body :deep(ul[data-type='taskList'] li) {
  display: flex;
}

.snapshot-content__body :deep(ul[data-type='taskList'] li > label) {
  flex: 0 0 auto;
  margin-right: 0.5rem;
  user-select: none;
}

.snapshot-content__body :deep(ul[data-type='taskList'] li > div) {
  flex: 1 1 auto;
}

.snapshot-content__body :deep(ul) {
  list-style: disc !important;
}

.snapshot-content__body :deep(ol) {
  list-style: decimal !important;
}

.snapshot-content__body :deep(code br) {
  display: initial;
}

/* 内容包裹 div 的首个块不留上边距（官方同款） */
.snapshot-content__body :deep(div > :first-child) {
  margin-top: 0 !important;
}

/* 单版本视图：官方形态直接渲染正文，滚动区占满 */
.snapshot-content__body--single {
  flex: 1;
  overflow: auto;
}

@media (max-width: 820px) {
  .snapshot-grid {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(180px, 40%) minmax(0, 1fr);
  }

  .snapshot-list {
    border-right: 0;
    border-bottom: 1px solid var(--bbs-border);
  }

  .diff-grid {
    grid-template-columns: minmax(0, 1fr);
    grid-auto-rows: minmax(160px, 1fr);
  }

  .diff-pane {
    border-right: 0;
    border-bottom: 1px solid var(--bbs-border);
  }
}
</style>
