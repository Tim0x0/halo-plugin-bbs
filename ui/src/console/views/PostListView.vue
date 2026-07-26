<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
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
  VStatusDot,
  VDropdownItem,
  VDropdownDivider,
  Toast,
  Dialog,
  IconAddCircle,
  IconRefreshLine,
  IconExternalLinkLine,
} from '@halo-dev/components'
import RiMegaphoneLine from '~icons/ri/megaphone-line'
import RiChat1Line from '~icons/ri/chat-1-line'
import { consoleApi, postCrudApi } from '@/api/bbs'
import { defaultPostForm, type BbsPostVo } from '@/types/bbs'
import PostSettingModal from '@/console/components/PostSettingModal.vue'

/**
 * Console 帖子管理列表，对标官方文章列表：服务端筛选（状态/类型/分类/标题关键词/排序）、
 * 批量删除、行内快捷操作（置顶/发布切换）、设置弹窗仅改 spec 元数据。
 */
const PHASE_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
}

const TYPE_LABELS: Record<string, string> = {
  ANNOUNCEMENT: '公告',
  POST: '帖子',
}

const router = useRouter()
const loading = ref(false)
const posts = ref<BbsPostVo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const phaseFilter = ref<string>()
const typeFilter = ref<string>()
const categoryFilter = ref<string>()
const sortFilter = ref<string>()
const keyword = ref('')
const categoryMap = ref<Record<string, string>>({})
const selected = ref<string[]>([])

// 快捷设置弹窗
const settingModalVisible = ref(false)
const settingPost = ref<BbsPostVo | null>(null)
const settingForm = ref(defaultPostForm())
const settingSaving = ref(false)

const phaseItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(PHASE_LABELS).map(([value, label]) => ({ label, value })),
])

const typeItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(TYPE_LABELS).map(([value, label]) => ({ label, value })),
])

const categoryItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(categoryMap.value).map(([value, label]) => ({ label, value })),
])

// 排序白名单键与后端 CONSOLE_SORTS 一致
const sortItems = [
  { label: '创建时间 降序', value: undefined },
  { label: '创建时间 升序', value: 'creationTimestamp,asc' },
  { label: '发布时间 降序', value: 'publishTime,desc' },
  { label: '发布时间 升序', value: 'publishTime,asc' },
]

const categoryOptions = computed(() =>
  Object.entries(categoryMap.value).map(([value, label]) => ({ label, value }))
)

const hasFilters = computed(
  () =>
    !!phaseFilter.value ||
    !!typeFilter.value ||
    !!categoryFilter.value ||
    !!sortFilter.value ||
    !!keyword.value
)

const isAllSelected = computed(
  () => posts.value.length > 0 && posts.value.every((p) => selected.value.includes(p.name))
)

// 查看/管理两级权限：只读用户（plugin:bbs:view）隐藏全部管理操作
const canManage = utils.permission.has(['plugin:bbs:manage'])

async function fetchCategories() {
  try {
    const { data } = await consoleApi.listCategories()
    const map: Record<string, string> = {}
    ;(data || []).forEach((c) => {
      map[c.name] = c.displayName
    })
    categoryMap.value = map
  } catch {
    /* 忽略：筛选项加载失败不阻塞列表 */
  }
}

async function fetchPosts() {
  loading.value = true
  selected.value = []
  try {
    const { data } = await consoleApi.listPosts({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      categoryName: categoryFilter.value,
      type: typeFilter.value,
      phase: phaseFilter.value,
      sort: sortFilter.value,
    })
    posts.value = data.items || []
    total.value = data.total || 0
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  page.value = 1
  fetchPosts()
}

function resetFilters() {
  phaseFilter.value = undefined
  typeFilter.value = undefined
  categoryFilter.value = undefined
  sortFilter.value = undefined
  keyword.value = ''
}

watch([phaseFilter, typeFilter, categoryFilter, sortFilter, keyword], applyFilter)

function toggleSelect(name: string) {
  selected.value = selected.value.includes(name)
    ? selected.value.filter((n) => n !== name)
    : [...selected.value, name]
}

function toggleSelectAll() {
  selected.value = isAllSelected.value ? [] : posts.value.map((p) => p.name)
}

async function doAction(
  name: string,
  op: 'publish' | 'unpublish' | 'approve' | 'pin' | 'unpin',
  okText: string
) {
  try {
    if (op === 'publish') await consoleApi.publishPost(name)
    else if (op === 'unpublish') await consoleApi.unpublishPost(name)
    else if (op === 'approve') await consoleApi.approvePost(name)
    else if (op === 'pin') await consoleApi.pinPost(name)
    else await consoleApi.unpinPost(name)
    Toast.success(okText)
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
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

async function batchAction(op: 'publish' | 'unpublish', okText: string) {
  const results = await Promise.allSettled(
    selected.value.map((name) =>
      op === 'publish' ? consoleApi.publishPost(name) : consoleApi.unpublishPost(name)
    )
  )
  reportBatch(results, okText)
  await fetchPosts()
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

async function confirmReject() {
  rejectSaving.value = true
  try {
    await consoleApi.rejectPost(rejectingPost.value!.name, rejectReason.value.trim())
    Toast.success('已驳回')
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
    const results = await Promise.allSettled(
      selected.value.map((name) =>
        postCrudApi.patch(name, [
          { op: 'add', path: '/spec/categoryName', value: batchCategoryName.value || null },
        ])
      )
    )
    reportBatch(results, '已设置分类')
    batchCategoryVisible.value = false
    await fetchPosts()
  } finally {
    batchSaving.value = false
  }
}

function batchDelete() {
  Dialog.warning({
    title: '批量删除',
    description: `确定删除选中的 ${selected.value.length} 篇帖子吗？该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      const results = await Promise.allSettled(
        selected.value.map((name) => consoleApi.deletePost(name))
      )
      reportBatch(results, '已删除')
      await fetchPosts()
    },
  })
}

function onDelete(post: BbsPostVo) {
  Dialog.warning({
    title: '删除帖子',
    description: `确定删除「${post.title}」吗？该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        await consoleApi.deletePost(post.name)
        Toast.success('已删除')
        await fetchPosts()
      } catch {
        /* 请求错误由全局拦截器提示 */
      }
    },
  })
}

function openSetting(post: BbsPostVo) {
  settingPost.value = post
  settingForm.value = {
    title: post.title || '',
    slug: post.slug || '',
    type: post.type || 'POST',
    categoryName: post.category?.name || '',
    autoExcerpt: !post.excerpt,
    excerpt: post.excerpt || '',
    content: '',
    pinned: !!post.pinned,
    pinPriority: post.pinPriority || 0,
  }
  settingModalVisible.value = true
}

async function saveSetting() {
  const f = settingForm.value
  if (!f.slug.trim()) {
    Toast.warning('别名不能为空')
    return
  }
  settingSaving.value = true
  try {
    await postCrudApi.patch(settingPost.value!.name, [
      { op: 'add', path: '/spec/slug', value: f.slug.trim() },
      { op: 'add', path: '/spec/type', value: f.type },
      { op: 'add', path: '/spec/categoryName', value: f.categoryName || null },
      { op: 'add', path: '/spec/excerpt', value: f.autoExcerpt ? '' : f.excerpt },
      { op: 'add', path: '/spec/pinned', value: f.pinned },
      { op: 'add', path: '/spec/pinPriority', value: f.pinPriority },
    ])
    Toast.success('已保存')
    settingModalVisible.value = false
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示（如别名冲突） */
  } finally {
    settingSaving.value = false
  }
}

function toEditor(name?: string) {
  router.push({ name: 'BbsPostEditor', query: name ? { name } : {} })
}

function formatTime(value?: string) {
  return value ? utils.date.format(value) : ''
}

function timeAgo(value?: string) {
  return value ? utils.date.timeAgo(value) : '—'
}

onMounted(() => {
  fetchCategories()
  fetchPosts()
})
</script>

<template>
  <VPageHeader title="BBS 社区">
    <template #icon>
      <RiMegaphoneLine class="header-icon" />
    </template>
    <template #actions>
      <VButton v-permission="['plugin:bbs:manage']" size="sm" :route="{ name: 'BbsCategories' }">
        分类管理
      </VButton>
      <VButton v-permission="['plugin:bbs:manage']" type="secondary" @click="toEditor()">
        <template #icon><IconAddCircle /></template>
        写帖子
      </VButton>
    </template>
  </VPageHeader>

  <div class="page-body">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="list-toolbar">
          <div class="list-toolbar__check">
            <input
              v-permission="['plugin:bbs:manage']"
              type="checkbox"
              class="bbs-checkbox"
              :checked="isAllSelected"
              @change="toggleSelectAll"
            />
          </div>
          <div class="list-toolbar__main">
            <SearchInput v-if="!selected.length" v-model="keyword" placeholder="搜索标题" />
            <VSpace v-else>
              <VButton @click="batchAction('publish', '已发布')">发布</VButton>
              <VButton @click="batchAction('unpublish', '已转为草稿')">转为草稿</VButton>
              <VButton @click="batchCategoryVisible = true">设置分类</VButton>
              <VButton type="danger" @click="batchDelete">删除</VButton>
            </VSpace>
          </div>
          <VSpace spacing="lg" class="list-toolbar__filters">
            <FilterCleanButton v-if="hasFilters" @click="resetFilters" />
            <FilterDropdown v-model="phaseFilter" label="状态" :items="phaseItems" />
            <FilterDropdown v-model="typeFilter" label="类型" :items="typeItems" />
            <FilterDropdown v-model="categoryFilter" label="分类" :items="categoryItems" />
            <FilterDropdown v-model="sortFilter" label="排序" :items="sortItems" />
            <div v-tooltip="'刷新'" class="refresh-btn" @click="fetchPosts">
              <IconRefreshLine
                class="refresh-btn__icon"
                :class="{ 'refresh-btn__icon--spin': loading }"
              />
            </div>
          </VSpace>
        </div>
      </template>

      <VLoading v-if="loading" />
      <Transition v-else-if="posts.length === 0" appear name="fade">
        <VEmpty title="暂无帖子" message="当前筛选下没有内容，可以刷新或发布一篇新帖子 / 公告">
          <template #actions>
            <VSpace>
              <VButton @click="fetchPosts">刷新</VButton>
              <VButton v-permission="['plugin:bbs:manage']" type="secondary" @click="toEditor()">
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
                v-permission="['plugin:bbs:manage']"
                type="checkbox"
                class="bbs-checkbox"
                :checked="selected.includes(post.name)"
                @change="toggleSelect(post.name)"
              />
            </template>
            <template #start>
              <VEntityField
                :title="post.title"
                max-width="30rem"
                :route="canManage ? { name: 'BbsPostEditor', query: { name: post.name } } : undefined"
              >
                <template #extra>
                  <a
                    v-if="post.phase === 'PUBLISHED'"
                    target="_blank"
                    :href="post.permalink"
                    class="entity-permalink"
                    @click.stop
                  >
                    <IconExternalLinkLine class="entity-permalink__icon" />
                  </a>
                </template>
                <template #description>
                  <div class="entity-meta">
                    <!-- 固定槽位：分类（无分类显示占位），保证各行左缘对齐 -->
                    <span v-if="post.category" class="entity-category">
                      <span
                        class="entity-category__dot"
                        :style="{ background: post.category.color || '#9ca3af' }"
                      ></span>
                      {{ post.category.displayName }}
                    </span>
                    <span v-else class="entity-category entity-category--none">
                      <span class="entity-category__dot"></span>
                      未分类
                    </span>
                    <!-- 条件徽标：跟在固定槽位之后 -->
                    <span
                      v-if="post.type === 'ANNOUNCEMENT'"
                      class="bbs-badge bbs-badge--announcement"
                    >
                      公告
                    </span>
                    <span v-if="post.pinned" class="bbs-badge bbs-badge--pinned">置顶</span>
                    <span
                      v-if="post.lastEditTime"
                      v-tooltip="formatTime(post.lastEditTime)"
                      class="entity-meta__faint"
                    >
                      已编辑
                    </span>
                  </div>
                </template>
              </VEntityField>
            </template>
            <template #end>
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
              <VEntityField width="3.5rem">
                <template #description>
                  <span v-tooltip="'评论数'" class="entity-comments">
                    <RiChat1Line class="entity-comments__icon" />
                    {{ post.commentCount ?? 0 }}
                  </span>
                </template>
              </VEntityField>
              <VEntityField width="5rem">
                <template #description>
                  <VStatusDot
                    v-if="post.phase === 'PUBLISHED'"
                    state="success"
                    text="已发布"
                  />
                  <VStatusDot
                    v-else-if="post.phase === 'PENDING'"
                    state="warning"
                    text="待审核"
                    animate
                  />
                  <VStatusDot
                    v-else-if="post.phase === 'REJECTED'"
                    v-tooltip="post.rejectReason ? `驳回原因：${post.rejectReason}` : ''"
                    state="error"
                    text="已驳回"
                  />
                  <VStatusDot v-else state="default" text="草稿" />
                </template>
              </VEntityField>
              <VEntityField width="7rem">
                <template #description>
                  <span
                    v-tooltip="formatTime(post.publishTime || post.creationTimestamp)"
                    class="entity-meta"
                  >
                    {{ timeAgo(post.publishTime || post.creationTimestamp) }}
                  </span>
                </template>
              </VEntityField>
            </template>
            <template v-if="canManage" #dropdownItems>
              <VDropdownItem @click="toEditor(post.name)">编辑</VDropdownItem>
              <VDropdownItem @click="openSetting(post)">设置</VDropdownItem>
              <VDropdownDivider />
              <template v-if="post.phase === 'PENDING'">
                <VDropdownItem @click="doAction(post.name, 'approve', '已通过并发布')">
                  通过
                </VDropdownItem>
                <VDropdownItem type="danger" @click="openReject(post)">驳回</VDropdownItem>
              </template>
              <VDropdownItem
                v-else-if="post.phase === 'PUBLISHED'"
                @click="doAction(post.name, 'unpublish', '已转为草稿')"
              >
                撤销发布
              </VDropdownItem>
              <VDropdownItem v-else @click="doAction(post.name, 'publish', '已发布')">
                发布
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
              <VDropdownDivider />
              <VDropdownItem type="danger" @click="onDelete(post)">删除</VDropdownItem>
            </template>
          </VEntity>
        </VEntityContainer>
      </Transition>

      <template #footer>
        <VPagination
          v-model:page="page"
          v-model:size="size"
          :total="total"
          :size-options="[20, 30, 50, 100]"
          @change="fetchPosts"
        />
      </template>
    </VCard>
  </div>

  <PostSettingModal
    v-if="settingModalVisible"
    v-model="settingForm"
    :categories="categoryOptions"
    :saving="settingSaving"
    @confirm="saveSetting"
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
      :help="`将展示给「${rejectingPost?.title}」的作者，可留空`"
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
      type="select"
      label="分类"
      clearable
      placeholder="选择分类（留空 = 清除分类）"
      :help="`将应用到选中的 ${selected.length} 篇帖子`"
      :options="categoryOptions"
    />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="batchSaving" @click="batchSetCategory">确定</VButton>
        <VButton @click="batchCategoryVisible = false">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.header-icon {
  margin-right: 0.5rem;
  align-self: center;
}

.page-body {
  margin: 0;
}

@media (min-width: 768px) {
  .page-body {
    margin: 1rem;
  }
}

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

.refresh-btn {
  display: flex;
  cursor: pointer;
  align-items: center;
  border-radius: var(--bbs-radius);
  padding: 0.25rem;
  transition: var(--bbs-transition);
}

.refresh-btn:hover {
  background: var(--bbs-bg-selected);
}

.refresh-btn__icon {
  height: 1rem;
  width: 1rem;
  color: var(--bbs-text-muted);
}

.refresh-btn__icon--spin {
  animation: bbs-spin 1s linear infinite;
  color: var(--bbs-text);
}

@keyframes bbs-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.entity-permalink {
  margin-left: 0.25rem;
  display: inline-flex;
  color: var(--bbs-text-faint);
  opacity: 0;
  transition: var(--bbs-transition);
}

.entity-permalink:hover {
  color: var(--bbs-text);
}

:deep(.group:hover) .entity-permalink,
.entity-permalink:focus-visible {
  opacity: 1;
}

.entity-permalink__icon {
  height: 0.875rem;
  width: 0.875rem;
}

.entity-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.625rem;
  font-size: 0.75rem;
  color: var(--bbs-text-muted);
}

.entity-meta__faint {
  color: var(--bbs-text-faint);
}

.entity-category {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
}

.entity-category__dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 2px;
  flex: none;
  background: var(--bbs-border);
}

.entity-category--none {
  color: var(--bbs-text-faint);
}

.entity-comments {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.75rem;
  color: var(--bbs-text-muted);
  font-variant-numeric: tabular-nums;
}

.entity-comments__icon {
  width: 0.875rem;
  height: 0.875rem;
  color: var(--bbs-text-faint);
}

.bbs-badge {
  border-radius: var(--bbs-radius);
  padding: 0.125rem 0.375rem;
  font-weight: 500;
}

.bbs-badge--announcement {
  background: var(--bbs-warning-bg);
  color: var(--bbs-warning);
}

.bbs-badge--pinned {
  background: var(--bbs-accent-bg);
  color: var(--bbs-accent);
}
</style>
