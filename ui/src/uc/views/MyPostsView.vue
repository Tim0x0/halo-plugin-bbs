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
  VPagination,
  VEntityContainer,
  VEntity,
  VEntityField,
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
import { axiosInstance } from '@halo-dev/api-client'
import { ucApi } from '@/api/bbs'
import { defaultPostForm, type BbsPostVo } from '@/types/bbs'
import PostSettingModal from '@/console/components/PostSettingModal.vue'

/**
 * UC「我的帖子」：登录用户管理自己发布的帖子（发帖 / 编辑 / 删除）。
 * 只读写 owner 为自己的数据（后端强制校验）。
 */
const router = useRouter()
const loading = ref(false)
const posts = ref<BbsPostVo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const phaseFilter = ref<string>()
const categoryFilter = ref<string>()

// 设置弹窗的分类选项：走公开接口（UC 用户无 Console 权限）
const categoryOptions = ref<{ label: string; value: string }[]>([])

async function fetchCategories() {
  try {
    const { data } = await axiosInstance.get<{ name: string; displayName: string }[]>(
      '/apis/api.bbs.timxs.com/v1alpha1/categories'
    )
    categoryOptions.value = data.map((c) => ({ label: c.displayName, value: c.name }))
  } catch {
    /* 忽略：分类加载失败不阻塞列表 */
  }
}

const phaseItems = computed(() => [
  { label: '全部', value: undefined },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '待审核', value: 'PENDING' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '草稿', value: 'DRAFT' },
])

const categoryItems = computed(() => [
  { label: '全部', value: undefined },
  ...categoryOptions.value.map((c) => ({ label: c.label, value: c.value })),
])

const hasFilters = computed(
  () => !!phaseFilter.value || !!categoryFilter.value || !!keyword.value
)

function resetFilters() {
  phaseFilter.value = undefined
  categoryFilter.value = undefined
  keyword.value = ''
}

async function fetchPosts() {
  loading.value = true
  try {
    const { data } = await ucApi.listMine({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      phase: phaseFilter.value,
      categoryName: categoryFilter.value,
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

watch([keyword, phaseFilter, categoryFilter], applyFilter)

function toEditor(name?: string) {
  router.push({ name: 'BbsUcPostEditor', query: name ? { name } : {} })
}

// 快捷设置弹窗（对齐官方 UC 文章列表）：改分类/别名/摘要，不动正文；
// UC 更新接口是全量提交，先取回正文一并回传
const settingVisible = ref(false)
const settingPost = ref<BbsPostVo | null>(null)
const settingForm = ref(defaultPostForm())
const settingSaving = ref(false)

async function openSetting(post: BbsPostVo) {
  try {
    const { data } = await ucApi.getMine(post.name)
    settingPost.value = post
    settingForm.value = {
      title: data.title,
      slug: data.slug || '',
      type: 'POST',
      categoryName: data.category?.name || '',
      autoExcerpt: !data.excerpt,
      excerpt: data.excerpt || '',
      content: data.content || '',
      pinned: false,
      pinPriority: 0,
    }
    settingVisible.value = true
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

async function saveSetting() {
  const f = settingForm.value
  settingSaving.value = true
  try {
    const { data } = await ucApi.update(settingPost.value!.name, {
      title: f.title,
      slug: f.slug,
      categoryName: f.categoryName,
      excerpt: f.autoExcerpt ? '' : f.excerpt,
      content: f.content,
    })
    Toast.success(data.spec?.phase === 'PENDING' ? '已保存，等待管理员审核' : '已保存')
    settingVisible.value = false
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    settingSaving.value = false
  }
}

function onDelete(post: BbsPostVo) {
  Dialog.warning({
    title: '删除帖子',
    description: `确定删除「${post.title}」吗？该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        await ucApi.delete(post.name)
        Toast.success('已删除')
        await fetchPosts()
      } catch {
        /* 请求错误由全局拦截器提示 */
      }
    },
  })
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
  <VPageHeader title="我的帖子">
    <template #icon>
      <RiMegaphoneLine class="header-icon" />
    </template>
    <template #actions>
      <VButton type="secondary" @click="toEditor()">
        <template #icon><IconAddCircle /></template>
        写帖子
      </VButton>
    </template>
  </VPageHeader>

  <div class="page-body">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="list-toolbar">
          <div class="list-toolbar__main">
            <SearchInput v-model="keyword" placeholder="搜索标题" />
          </div>
          <VSpace spacing="lg" class="list-toolbar__filters">
            <FilterCleanButton v-if="hasFilters" @click="resetFilters" />
            <FilterDropdown v-model="phaseFilter" label="状态" :items="phaseItems" />
            <FilterDropdown v-model="categoryFilter" label="分类" :items="categoryItems" />
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
        <VEmpty title="还没有帖子" message="发布你的第一篇帖子，与大家分享内容">
          <template #actions>
            <VButton type="secondary" @click="toEditor()">
              <template #icon><IconAddCircle /></template>
              写帖子
            </VButton>
          </template>
        </VEmpty>
      </Transition>
      <Transition v-else appear name="fade">
        <VEntityContainer>
          <VEntity v-for="post in posts" :key="post.name">
            <template #start>
              <VEntityField :title="post.title" max-width="30rem">
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
                    <!-- 条件展示：徽标 / 摘要 / 驳回原因 -->
                    <span
                      v-if="post.type === 'ANNOUNCEMENT'"
                      class="bbs-badge bbs-badge--announcement"
                    >
                      公告
                    </span>
                    <span v-if="post.pinned" class="bbs-badge bbs-badge--pinned">置顶</span>
                    <span
                      v-if="post.phase === 'REJECTED' && post.rejectReason"
                      class="entity-reject"
                    >
                      驳回原因：{{ post.rejectReason }}
                    </span>
                  </div>
                </template>
              </VEntityField>
            </template>
            <template #end>
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
            <template #dropdownItems>
              <VDropdownItem @click="toEditor(post.name)">编辑</VDropdownItem>
              <VDropdownItem @click="openSetting(post)">设置</VDropdownItem>
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
          :size-options="[20, 30, 50]"
          @change="fetchPosts"
        />
      </template>
    </VCard>
  </div>

  <PostSettingModal
    v-if="settingVisible"
    v-model="settingForm"
    :categories="categoryOptions"
    :managed="false"
    :saving="settingSaving"
    @confirm="saveSetting"
    @close="settingVisible = false"
  />
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
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
  background: var(--bbs-bg-soft);
  padding: 0.75rem 1rem;
}

.list-toolbar__main {
  display: flex;
  flex: 1;
  align-items: center;
}

.list-toolbar__filters {
  flex-wrap: wrap;
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
  transition: var(--bbs-transition);
}

.entity-permalink:hover {
  color: var(--bbs-text);
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

.entity-category {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  flex: none;
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
  flex: none;
}

.bbs-badge--announcement {
  background: var(--bbs-warning-bg);
  color: var(--bbs-warning);
}

.bbs-badge--pinned {
  background: var(--bbs-accent-bg);
  color: var(--bbs-accent);
}

.entity-reject {
  color: var(--bbs-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}
</style>
