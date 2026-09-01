<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { refDebounced } from '@vueuse/core'
import {
  Dialog,
  IconRefreshLine,
  Toast,
  VAvatar,
  VButton,
  VDropdownDivider,
  VDropdownItem,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VModal,
  VPagination,
  VSpace,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import DOMPurify from 'dompurify'
import { consoleApi } from '@/api/bbs'
import { formatTime, timeAgo } from '@/utils/date'
import type { BbsCommentAdminVo, BbsReplyAdminVo } from '@/types/bbs'
import UserFilterDropdown from './UserFilterDropdown.vue'

/**
 * 帖子评论管理弹窗：列表评论列点击后的唯一入口。
 *
 * 交互逐项对齐官方评论管理（SubjectQueryCommentList + CommentListItem）：
 * 筛选（审核状态 / 作者 / 排序 / 关键词）+ 行内通过 / 取消通过 / 删除 /
 * 通过全部回复 + 展开楼中楼逐条管理 + 版主回复。数据走 BBS 作用域端点
 * （服务端强制 subjectRef 归属），界面与操作语义与官方一致。
 */
const props = defineProps<{
  postName: string
}>()

const emit = defineEmits<{ close: [] }>()

const modal = ref<InstanceType<typeof VModal>>()
const canModerate = utils.permission.has(['plugin:bbs:moderate'])

// —— 筛选与分页（弹窗内状态，不挂 URL）——
const loading = ref(true)
const comments = ref<BbsCommentAdminVo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const approved = ref<string | undefined>(undefined)
const sort = ref<string | undefined>(undefined)
const owner = ref<string | undefined>(undefined)
const keywordDebounced = refDebounced(computed(() => keyword.value), 300)

const approvedItems = [
  { label: '全部', value: undefined },
  { label: '已通过', value: 'true' },
  { label: '待审核', value: 'false' },
]

const sortItems = [
  { label: '默认', value: undefined },
  { label: '创建时间 降序', value: 'metadata.creationTimestamp,desc' },
  { label: '创建时间 升序', value: 'metadata.creationTimestamp,asc' },
]

const hasFilters = computed(
  () => approved.value !== undefined || !!sort.value || !!owner.value
)

function resetFilters() {
  approved.value = undefined
  sort.value = undefined
  owner.value = undefined
  keyword.value = ''
}

let fetchSeq = 0
let pollTimer: number | undefined

async function fetchComments(silent = false) {
  const seq = ++fetchSeq
  if (!silent) {
    loading.value = true
  }
  try {
    const { data } = await consoleApi.listPostComments(props.postName, {
      page: page.value,
      size: size.value,
      keyword: keywordDebounced.value.trim() || undefined,
      approved: approved.value,
      owner: owner.value,
      sort: sort.value,
    })
    if (seq !== fetchSeq) {
      return
    }
    comments.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    // HTTP 错误由全局拦截器提示；同步异常打到控制台，避免被静默吞掉
    console.error('[bbs] 评论列表加载失败', error)
  } finally {
    if (seq === fetchSeq && !silent) {
      loading.value = false
    }
  }
  // 官方同款：有删除中的条目时 1000ms 轮询，等 finalizer 跑完真正消失
  window.clearTimeout(pollTimer)
  const deleting =
    comments.value.some((item) => item.deleting) ||
    Object.values(expanded).some((state) =>
      state.items.some((reply) => reply.deleting)
    )
  if (deleting) {
    pollTimer = window.setTimeout(() => {
      void fetchComments(true)
      void refreshExpandedReplies()
    }, 1000)
  }
}

watch(
  () => [keywordDebounced.value, approved.value, sort.value, owner.value],
  () => {
    page.value = 1
  }
)

watch(
  () => [page.value, size.value, keywordDebounced.value, approved.value, sort.value, owner.value],
  () => {
    void fetchComments()
  },
  { immediate: true }
)

// —— 楼中楼展开（按需拉取，含未审核）——
interface ReplyState {
  loading: boolean
  items: BbsReplyAdminVo[]
  total: number
}

const expanded = reactive<Record<string, ReplyState>>({})

function isExpanded(commentName: string) {
  return commentName in expanded
}

async function loadReplies(commentName: string) {
  const state = expanded[commentName]
  state.loading = true
  try {
    const { data } = await consoleApi.listCommentReplies(props.postName, commentName, {
      page: 1,
      size: 100,
    })
    state.items = data.items || []
    state.total = data.total || 0
  } catch (error) {
    console.error('[bbs] 回复列表加载失败', error)
  } finally {
    state.loading = false
  }
}

function toggleReplies(comment: BbsCommentAdminVo) {
  if (isExpanded(comment.name)) {
    delete expanded[comment.name]
    return
  }
  expanded[comment.name] = { loading: false, items: [], total: 0 }
  void loadReplies(comment.name)
}

function refreshExpandedReplies() {
  for (const commentName of Object.keys(expanded)) {
    void loadReplies(commentName)
  }
}

// —— 评论操作 ——
async function act(task: () => Promise<unknown>, okText: string) {
  try {
    await task()
    Toast.success(okText)
    await Promise.all([fetchComments(true), Promise.resolve(refreshExpandedReplies())])
  } catch {
    /* 请求错误由全局拦截器提示 */
  }
}

function onApprove(comment: BbsCommentAdminVo) {
  void act(
    () => consoleApi.approveComment(props.postName, comment.name),
    '已通过'
  )
}

function onUnapprove(comment: BbsCommentAdminVo) {
  void act(
    () => consoleApi.unapproveComment(props.postName, comment.name),
    '已取消通过'
  )
}

function onDeleteComment(comment: BbsCommentAdminVo) {
  Dialog.warning({
    title: '删除评论',
    description: '将删除该评论及其全部回复，该操作不可恢复。',
    confirmType: 'danger',
    onConfirm: async () => {
      await act(
        () => consoleApi.deleteComment(props.postName, comment.name),
        '删除成功'
      )
      delete expanded[comment.name]
    },
  })
}

function onApproveAllReplies(comment: BbsCommentAdminVo) {
  Dialog.warning({
    title: '通过全部回复',
    description: '将通过该评论下所有未审核的回复。',
    onConfirm: async () => {
      try {
        const { data } = await consoleApi.approveUnreviewedReplies(
          props.postName,
          comment.name
        )
        Toast.success(`已通过 ${data.approvedCount ?? 0} 条回复`)
        await Promise.all([fetchComments(true), Promise.resolve(refreshExpandedReplies())])
      } catch {
        /* 请求错误由全局拦截器提示 */
      }
    },
  })
}

// —— 回复操作 ——
function onApproveReply(commentName: string, reply: BbsReplyAdminVo) {
  void act(
    () => consoleApi.approveReply(props.postName, commentName, reply.name),
    '已通过'
  )
}

function onUnapproveReply(commentName: string, reply: BbsReplyAdminVo) {
  void act(
    () => consoleApi.unapproveReply(props.postName, commentName, reply.name),
    '已取消通过'
  )
}

function onDeleteReply(commentName: string, reply: BbsReplyAdminVo) {
  Dialog.warning({
    title: '删除回复',
    description: '将删除该回复，该操作不可恢复。',
    confirmType: 'danger',
    onConfirm: async () => {
      await act(
        () => consoleApi.deleteReply(props.postName, commentName, reply.name),
        '删除成功'
      )
    },
  })
}

// —— 版主回复（弹窗内再开弹窗，统一 mount-to-body）——
const replyBoxComment = ref<BbsCommentAdminVo | null>(null)
const replyRaw = ref('')
const replySaving = ref(false)

function openReplyBox(comment: BbsCommentAdminVo) {
  replyRaw.value = ''
  replyBoxComment.value = comment
}

async function submitReply() {
  const target = replyBoxComment.value
  if (!target || !replyRaw.value.trim()) {
    Toast.warning('回复内容不能为空')
    return
  }
  replySaving.value = true
  try {
    await consoleApi.createReply(props.postName, target.name, {
      raw: replyRaw.value.trim(),
    })
    Toast.success('已回复')
    replyBoxComment.value = null
    if (isExpanded(target.name)) {
      await loadReplies(target.name)
    }
    await fetchComments(true)
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    replySaving.value = false
  }
}

function sanitize(html?: string) {
  return DOMPurify.sanitize(html || '')
}

onBeforeUnmount(() => {
  window.clearTimeout(pollTimer)
})
</script>

<template>
  <!--
    单根容器：本组件含两个 VModal（评论列表 + 版主回复），并列多根是 Fragment，
    在父级 v-if 挂卸时锚点对不上会 insertBefore 崩溃；包一层稳定 div。
  -->
  <div>
  <VModal
    ref="modal"
    title="评论"
    :width="1400"
    :centered="false"
    :layer-closable="true"
    mount-to-body
    @close="emit('close')"
  >
    <!--
      稳定容器包住全部动态内容：VModal 内容区被 overlayscrollbars 接管、重排过，
      v-if 切换（加载态 ⇄ 列表）直接落在内容区顶层会 insertBefore 崩溃。
    -->
    <div>
    <!-- 工具条：对齐官方评论列表（搜索 / 状态 / 作者 / 排序 / 刷新） -->
    <div class="comment-toolbar">
      <SearchInput v-model="keyword" class="comment-toolbar__search" />
      <VSpace spacing="lg" class="comment-toolbar__filters">
        <FilterCleanButton v-if="hasFilters" @click="resetFilters" />
        <FilterDropdown v-model="approved" label="状态" :items="approvedItems" />
        <UserFilterDropdown
          v-if="utils.permission.has(['system:users:view'])"
          v-model="owner"
          label="作者"
        />
        <FilterDropdown v-model="sort" label="排序" :items="sortItems" />
        <div v-tooltip="'刷新'" class="bbs-refresh-btn" @click="fetchComments()">
          <IconRefreshLine
            class="bbs-refresh-btn__icon"
            :class="{ 'bbs-refresh-btn__icon--spin': loading }"
          />
        </div>
      </VSpace>
    </div>

    <VLoading v-if="loading" />
    <Transition v-else-if="comments.length === 0" appear name="fade">
      <VEmpty title="暂无评论" message="该帖还没有收到评论">
        <template #actions>
          <VButton @click="fetchComments()">刷新</VButton>
        </template>
      </VEmpty>
    </Transition>
    <Transition v-else appear name="fade">
      <div class="comment-list">
        <VEntityContainer>
          <VEntity v-for="comment in comments" :key="comment.name">
            <template #start>
              <VEntityField width="100%" max-width="100%">
                <template #description>
                  <div class="comment-item">
                    <div class="comment-item__head">
                      <VAvatar
                        :src="comment.owner?.avatar"
                        :alt="comment.owner?.displayName"
                        size="xs"
                        circle
                      />
                      <span class="comment-item__author">
                        {{ comment.owner?.displayName || '匿名' }}
                      </span>
                      <VTag v-if="comment.hidden">私密</VTag>
                      <VTag v-if="comment.top">置顶</VTag>
                    </div>
                    <div class="comment-item__content" v-html="sanitize(comment.content)" />
                    <div class="comment-item__foot">
                      <span class="comment-item__link" @click="toggleReplies(comment)">
                        {{ comment.replyCount ?? 0 }} 条回复
                      </span>
                      <span
                        v-if="canModerate"
                        class="comment-item__link"
                        @click="openReplyBox(comment)"
                      >
                        回复
                      </span>
                    </div>
                  </div>
                </template>
              </VEntityField>
            </template>
            <template #end>
              <VEntityField v-if="!comment.approved">
                <template #description>
                  <VStatusDot state="warning" animate text="待审核" />
                </template>
              </VEntityField>
              <VEntityField v-if="comment.deleting">
                <template #description>
                  <VStatusDot v-tooltip="'删除中'" state="warning" animate />
                </template>
              </VEntityField>
              <VEntityField width="7rem">
                <template #description>
                  <span
                    v-tooltip="formatTime(comment.creationTime)"
                    class="comment-item__time"
                  >
                    {{ timeAgo(comment.creationTime) }}
                  </span>
                </template>
              </VEntityField>
            </template>
            <template v-if="canModerate" #dropdownItems>
              <VDropdownItem v-if="!comment.approved" @click="onApprove(comment)">
                通过
              </VDropdownItem>
              <VDropdownItem @click="onApproveAllReplies(comment)">通过全部回复</VDropdownItem>
              <VDropdownDivider />
              <VDropdownItem v-if="comment.approved" type="danger" @click="onUnapprove(comment)">
                取消通过
              </VDropdownItem>
              <VDropdownItem type="danger" @click="onDeleteComment(comment)">删除</VDropdownItem>
            </template>

            <!-- 楼中楼（含未审核；逐条可审批 / 删除） -->
            <template v-if="isExpanded(comment.name)" #footer>
              <div class="reply-block">
                <VLoading v-if="expanded[comment.name].loading" />
                <VEmpty
                  v-else-if="expanded[comment.name].items.length === 0"
                  title="还没有回复"
                  message="该评论下还没有回复"
                />
                <template v-else>
                  <VEntityContainer>
                    <VEntity
                      v-for="reply in expanded[comment.name].items"
                      :key="reply.name"
                    >
                      <template #start>
                        <VEntityField width="100%" max-width="100%">
                          <template #description>
                            <div class="comment-item">
                              <div class="comment-item__head">
                                <VAvatar
                                  :src="reply.owner?.avatar"
                                  :alt="reply.owner?.displayName"
                                  size="xs"
                                  circle
                                />
                                <span class="comment-item__author">
                                  {{ reply.owner?.displayName || '匿名' }}
                                </span>
                                <VTag v-if="reply.hidden">私密</VTag>
                              </div>
                              <div
                                class="comment-item__content"
                                v-html="sanitize(reply.content)"
                              />
                            </div>
                          </template>
                        </VEntityField>
                      </template>
                      <template #end>
                        <VEntityField v-if="!reply.approved">
                          <template #description>
                            <VStatusDot state="warning" animate text="待审核" />
                          </template>
                        </VEntityField>
                        <VEntityField v-if="reply.deleting">
                          <template #description>
                            <VStatusDot v-tooltip="'删除中'" state="warning" animate />
                          </template>
                        </VEntityField>
                        <VEntityField width="7rem">
                          <template #description>
                            <span
                              v-tooltip="formatTime(reply.creationTime)"
                              class="comment-item__time"
                            >
                              {{ timeAgo(reply.creationTime) }}
                            </span>
                          </template>
                        </VEntityField>
                      </template>
                      <template v-if="canModerate" #dropdownItems>
                        <VDropdownItem
                          v-if="!reply.approved"
                          @click="onApproveReply(comment.name, reply)"
                        >
                          通过
                        </VDropdownItem>
                        <VDropdownItem
                          v-else
                          type="danger"
                          @click="onUnapproveReply(comment.name, reply)"
                        >
                          取消通过
                        </VDropdownItem>
                        <VDropdownItem
                          type="danger"
                          @click="onDeleteReply(comment.name, reply)"
                        >
                          删除
                        </VDropdownItem>
                      </template>
                    </VEntity>
                  </VEntityContainer>
                  <p
                    v-if="
                      expanded[comment.name].total > expanded[comment.name].items.length
                    "
                    class="reply-block__more"
                  >
                    还有 {{
                      expanded[comment.name].total - expanded[comment.name].items.length
                    }} 条回复（单次最多展开 100 条）
                  </p>
                </template>
              </div>
            </template>
          </VEntity>
        </VEntityContainer>
      </div>
    </Transition>

    <div class="comment-pagination">
      <VPagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        :size-options="[20, 30, 50, 100]"
      />
    </div>
    </div>

    <template #footer>
      <VButton @click="modal?.close()">关闭</VButton>
    </template>
  </VModal>

  <!-- 版主回复：弹窗内弹窗，稳定挂载 + mount-to-body -->
  <VModal
    v-if="replyBoxComment"
    title="回复评论"
    :width="500"
    mount-to-body
    @close="replyBoxComment = null"
  >
    <FormKit
      v-model="replyRaw"
      type="textarea"
      label="回复内容"
      :rows="4"
      :help="`以你的身份回复「${replyBoxComment?.owner?.displayName || '匿名'}」的评论，直接通过`"
    />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="replySaving" @click="submitReply">回复</VButton>
        <VButton @click="replyBoxComment = null">取消</VButton>
      </VSpace>
    </template>
  </VModal>
  </div>
</template>

<style scoped>
.comment-toolbar {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1rem;
}

@media (min-width: 640px) {
  .comment-toolbar {
    flex-direction: row;
    align-items: center;
  }
}

.comment-toolbar__search {
  flex: 1;
}

.comment-toolbar__filters {
  flex-wrap: wrap;
}

.comment-list {
  max-height: 60vh;
  overflow: auto;
}

.comment-item {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.comment-item__head {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.comment-item__author {
  font-size: 0.875rem;
  font-weight: 500;
}

.comment-item__content {
  overflow-wrap: anywhere;
  font-size: 0.875rem;
  color: var(--bbs-text, #111827);
}

.comment-item__content :deep(p) {
  margin: 0;
}

.comment-item__foot {
  display: flex;
  gap: 0.75rem;
  font-size: 0.75rem;
}

.comment-item__link {
  cursor: pointer;
  color: var(--bbs-text-muted, #6b7280);
}

.comment-item__link:hover {
  color: var(--bbs-text, #111827);
}

.comment-item__time {
  font-size: 0.75rem;
  color: var(--bbs-text-muted, #6b7280);
}

.reply-block {
  padding-left: 2rem;
}

.reply-block__more {
  margin: 0.25rem 0 0;
  font-size: 0.75rem;
  color: var(--bbs-text-faint, #9ca3af);
}

.comment-pagination {
  margin-top: 1rem;
}
/* bbs-refresh-btn 系列见 styles/tokens.css */
</style>
