<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { refDebounced } from '@vueuse/core'
import { useRouter } from 'vue-router'
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
  VDropdownItem,
  VDropdownDivider,
  Toast,
  Dialog,
  IconAddCircle,
  IconRefreshLine,
} from '@halo-dev/components'
import RiMegaphoneLine from '~icons/ri/megaphone-line'
import { publicApi, ucApi } from '@/api/bbs'
import { useRouteQuery } from '@vueuse/router'
import { BBS_PHASE_LABELS, BBS_TYPE_LABELS } from '@/utils/post-labels'
import { formatTime, timeAgo } from '@/utils/date'
import {
  defaultPostForm,
  postFormFrom,
  type BbsPostVo,
  type CategoryVo,
  type PostFormState,
} from '@/types/bbs'
import PostSettingModal from '@/console/components/PostSettingModal.vue'
import CategoryFilterDropdown from '@/console/components/CategoryFilterDropdown.vue'
import PostEntityStart from '@/shared/PostEntityStart.vue'
import PostStatusEnd from '@/shared/PostStatusEnd.vue'
import PostCommentsField from '@/shared/PostCommentsField.vue'
import PostLockField from '@/shared/PostLockField.vue'
import PostModerationRecords from '@/shared/PostModerationRecords.vue'
import SubmitNoteModal from '@/shared/SubmitNoteModal.vue'

/**
 * UC「我的帖子」：登录用户管理自己发布的帖子（发帖 / 编辑 / 删除）。
 * 只读写 owner 为自己的数据（后端强制校验）。
 */
const router = useRouter()
const loading = ref(false)
const posts = ref<BbsPostVo[]>([])
const total = ref(0)
// 筛选与分页挂到 URL（同 Console 列表）
const filters = reactive({
  page: useRouteQuery<number>('page', 1, { transform: Number }),
  size: useRouteQuery<number>('size', 20, { transform: Number }),
  keyword: useRouteQuery<string>('keyword', ''),
  phase: useRouteQuery<string | undefined>('phase'),
  category: useRouteQuery<string | undefined>('category'),
  type: useRouteQuery<string | undefined>('type'),
})

// 分类数据走公开接口（UC 用户无 Console 权限），同时供筛选下拉与设置弹窗使用
const categories = ref<CategoryVo[]>([])

async function fetchCategories() {
  try {
    const { data } = await publicApi.listCategories()
    categories.value = data || []
  } catch (error) {
    // 分类加载失败不阻塞列表；但把错误打到控制台——空 catch 会把同步异常一并吞掉且
    // 无任何痕迹。
    console.error('[bbs] 分类加载失败', error)
  }
}

const phaseItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(BBS_PHASE_LABELS).map(([value, label]) => ({ label, value })),
])

// UC 用户不能发公告，类型筛选不含 ANNOUNCEMENT
const typeItems = computed(() => [
  { label: '全部', value: undefined },
  ...Object.entries(BBS_TYPE_LABELS)
    .filter(([value]) => value !== 'ANNOUNCEMENT')
    .map(([value, label]) => ({ label, value })),
])

const hasFilters = computed(
  () => !!filters.phase || !!filters.category || !!filters.type || !!filters.keyword
)

function resetFilters() {
  filters.phase = undefined
  filters.category = undefined
  filters.type = undefined
  filters.keyword = ''
}

const keywordDebounced = refDebounced(
  computed(() => filters.keyword),
  300
)
let fetchSeq = 0

async function fetchPosts() {
  const seq = ++fetchSeq
  loading.value = true
  try {
    const { data } = await ucApi.listMine({
      page: filters.page,
      size: filters.size,
      keyword: (keywordDebounced.value || '').trim() || undefined,
      phase: filters.phase,
      categoryName: filters.category,
      type: filters.type,
    })
    if (seq !== fetchSeq) {
      return
    }
    posts.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    // HTTP 错误由全局拦截器提示；这里再把错误打到控制台——空 catch 会把拼参数时的
    // 同步异常一并吞掉且无任何痕迹。
    console.error('[bbs] 我的帖子加载失败', error)
  } finally {
    if (seq === fetchSeq) {
      loading.value = false
    }
  }
}

// 筛选变化回到第 1 页；随后的统一 watch 只发一次请求
watch(
  () => [keywordDebounced.value, filters.phase, filters.category, filters.type],
  () => {
    filters.page = 1
  }
)

// 唯一的请求入口（VPagination 不单独绑 @change，否则翻页触发两次）
watch(
  () => [
    filters.page,
    filters.size,
    keywordDebounced.value,
    filters.phase,
    filters.category,
    filters.type,
  ],
  fetchPosts,
  { immediate: true }
)

function toEditor(name?: string) {
  router.push({ name: 'BbsUcPostEditor', query: name ? { name } : {} })
}

// 快捷设置弹窗（对齐官方 UC 文章列表）：改分类/别名/摘要，不动正文；
// 所有“保存”只更新工作稿，已发布帖不会因此改变前台版本。
const settingVisible = ref(false)
const settingPost = ref<BbsPostVo | null>(null)
const settingForm = ref(defaultPostForm())
const settingSaving = ref(false)
const settingPublishing = ref(false)
/** 审核记录弹窗的目标帖名；审核留痕的唯一入口是列表行下拉 */
const moderationName = ref('')

async function openSetting(post: BbsPostVo) {
  if (post.locked) {
    Toast.warning('该帖子已被锁定，无法修改')
    return
  }
  try {
    const { data } = await ucApi.getMine(post.name)
    settingPost.value = post
    // 回填真实类型（讨论 / 问答可互改；公告在表单中锁定展示）；UC 侧无置顶特权
    settingForm.value = postFormFrom(data, { managed: false })
    settingVisible.value = true
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

function settingBodyFrom(f: PostFormState) {
  return {
    title: f.title,
    slug: f.slug,
    type: f.type,
    categoryName: f.categoryName,
    excerpt: f.excerpt,
    autoExcerpt: f.autoExcerpt,
  }
}

function settingBody() {
  return settingBodyFrom(settingForm.value)
}

async function saveSetting() {
  const post = settingPost.value!
  settingSaving.value = true
  try {
    await ucApi.saveDraft(post.name, settingBody())
    Toast.success('保存成功')
    settingVisible.value = false
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    settingSaving.value = false
  }
}

/**
 * UC 主操作：未发布 / 已驳回显示「提交」（状态差异由状态列表达）；
 * 已发布帖有未提交修改时提交修改稿；待审核中不显示（已在队列）。
 */
const settingPrimaryAction = computed<'submit' | undefined>(() => {
  const post = settingPost.value
  if (!post || post.phase === 'PENDING') {
    return undefined
  }
  if (post.phase === 'PUBLISHED') {
    return post.hasDraft && post.draftPhase !== 'PENDING' ? 'submit' : undefined
  }
  return 'submit'
})

const settingPrimaryLabel = computed(() =>
  settingPost.value?.phase === 'PUBLISHED' ? '提交修改' : '提交'
)

// —— 提交附言弹窗：与驳回弹窗同范式，附言可选；确认后跑真正的提交续体 ——
const noteVisible = ref(false)
const noteSaving = ref(false)
let noteContinuation: ((note: string) => Promise<void>) | null = null

function askSubmitNote(continuation: (note: string) => Promise<void>) {
  noteContinuation = continuation
  noteVisible.value = true
}

async function confirmSubmitNote(note: string) {
  noteSaving.value = true
  try {
    await noteContinuation?.(note)
    noteVisible.value = false
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    noteSaving.value = false
  }
}

function submitFromSetting() {
  askSubmitNote(async (note) => {
    const post = settingPost.value!
    settingPublishing.value = true
    try {
      await ucApi.submit(post.name, {
        ...settingBody(),
        submitNote: note || undefined,
      })
      Toast.success('已提交')
      settingVisible.value = false
      await fetchPosts()
    } finally {
      settingPublishing.value = false
    }
  })
}

/**
 * 列表直接提交（草稿 / 已驳回）：缺分类先去设置弹窗补齐（弹窗里有「提交」）；
 * 否则按帖子现有元数据原样提交——正文不传，服务端保留快照链内容。
 */
function submitPost(post: BbsPostVo) {
  if (!post.category) {
    void openSetting(post)
    return
  }
  askSubmitNote(async (note) => {
    const { data } = await ucApi.getMine(post.name)
    const f = postFormFrom(data, { managed: false })
    await ucApi.submit(post.name, {
      ...settingBodyFrom(f),
      submitNote: note || undefined,
    })
    Toast.success('已提交')
    await fetchPosts()
  })
}

/** 撤回待审核提交：新帖退回草稿；修改稿退回草稿态，前台发布版不受影响 */
async function withdrawPost(post: BbsPostVo) {
  try {
    await ucApi.withdraw(post.name)
    Toast.success('已撤回，帖子回到草稿状态')
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

/** 问答帖已解决切换（发帖人权利；锁定帖由后端拒绝并提示） */
async function toggleSolved(post: BbsPostVo) {
  try {
    if (post.solved) {
      await ucApi.unsolve(post.name)
      Toast.success('已取消已解决标记')
    } else {
      await ucApi.solve(post.name)
      Toast.success('已标记为已解决')
    }
    await fetchPosts()
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

function toEditorGuarded(post: BbsPostVo) {
  if (post.locked) {
    Toast.warning('该帖子已被锁定，无法编辑')
    return
  }
  toEditor(post.name)
}

function onDelete(post: BbsPostVo) {
  Dialog.warning({
    title: '删除帖子',
    description: `确定删除「${post.title}」吗？将移入回收站，管理员可恢复。`,
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

onMounted(() => {
  fetchCategories()
})
</script>

<template>
  <VPageHeader title="我的帖子">
    <template #icon>
      <RiMegaphoneLine class="bbs-header-icon" />
    </template>
    <template #actions>
      <VButton type="secondary" @click="toEditor()">
        <template #icon><IconAddCircle /></template>
        写帖子
      </VButton>
    </template>
  </VPageHeader>

  <div class="bbs-page-body">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="list-toolbar">
          <div class="list-toolbar__main">
            <SearchInput v-model="filters.keyword" placeholder="搜索标题" />
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
        <VEmpty title="还没有帖子" message="保存或提交你的第一篇帖子">
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
              <!-- 标题点击进编辑器（对齐 console 列表）；锁定帖作者不可编辑，不给路由。
                   驳回原因不占行面：完整留痕在审核记录弹窗（行下拉菜单），状态列悬停可见 -->
              <PostEntityStart
                :post="post"
                :title-route="
                  post.locked
                    ? undefined
                    : { name: 'BbsUcPostEditor', query: { name: post.name } }
                "
              />
            </template>
            <template #end>
              <PostCommentsField :count="post.commentsCount" />
              <PostLockField :post="post" readonly />
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
            <template #dropdownItems>
              <VDropdownItem :disabled="post.locked" @click="toEditorGuarded(post)">
                编辑
              </VDropdownItem>
              <VDropdownItem :disabled="post.locked" @click="openSetting(post)">
                设置
              </VDropdownItem>
              <!-- 未发布 / 已驳回可直接提交；已发布帖有未提交修改时提交修改稿；
                   待审核中不显示（已在队列，用「取消提交」）——修改稿待审核同理排除 -->
              <VDropdownItem
                v-if="
                  (post.phase === 'DRAFT'
                    || post.phase === 'REJECTED'
                    || (post.phase === 'PUBLISHED'
                      && post.hasDraft
                      && post.draftPhase !== 'PENDING'))
                    && !post.locked
                "
                @click="submitPost(post)"
              >
                {{ post.phase === 'PUBLISHED' ? '提交修改' : '提交' }}
              </VDropdownItem>
              <!-- 待审核可撤回：新帖退回草稿；修改稿退回草稿态，前台发布版不受影响。
                   锁定帖作者不可操作（后端归属校验同样会拒） -->
              <VDropdownItem
                v-if="(post.phase === 'PENDING' || post.draftPhase === 'PENDING') && !post.locked"
                @click="withdrawPost(post)"
              >
                取消提交
              </VDropdownItem>
              <VDropdownItem @click="moderationName = post.name">审核记录</VDropdownItem>
              <VDropdownItem
                v-if="post.type === 'QUESTION' && !post.locked"
                @click="toggleSolved(post)"
              >
                {{ post.solved ? '取消已解决' : '标记已解决' }}
              </VDropdownItem>
              <VDropdownDivider />
              <VDropdownItem type="danger" :disabled="post.locked" @click="onDelete(post)">
                删除
              </VDropdownItem>
            </template>
          </VEntity>
        </VEntityContainer>
      </Transition>

      <template #footer>
        <VPagination
          v-model:page="filters.page"
          v-model:size="filters.size"
          :total="total"
          :size-options="[20, 30, 50]"
        />
      </template>
    </VCard>
  </div>

  <PostSettingModal
    v-if="settingVisible"
    v-model="settingForm"
    :categories="categories"
    :managed="false"
    :post-name="settingPost?.name"
    :saving="settingSaving"
    :publishing="settingPublishing"
    :primary-action="settingPrimaryAction"
    :primary-label="settingPrimaryLabel"
    @confirm="saveSetting"
    @primary="submitFromSetting"
    @close="settingVisible = false"
  />

  <SubmitNoteModal
    v-if="noteVisible"
    :saving="noteSaving"
    @close="noteVisible = false"
    @confirm="confirmSubmitNote"
  />

  <!-- 审核记录：唯一入口在行下拉菜单（编辑器不挂此按钮） -->
  <PostModerationRecords
    v-if="moderationName"
    :post-name="moderationName"
    mode="uc"
    @close="moderationName = ''"
  />
</template>

<style scoped>
/* bbs-header-icon / bbs-page-body / bbs-refresh-btn / bbs-entity-time 见 styles/tokens.css */
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
</style>
