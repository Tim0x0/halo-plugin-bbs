<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VEmpty, VLoading, VModal } from '@halo-dev/components'
import { formatDate } from '@/utils/date'
import { consoleApi, ucApi } from '@/api/bbs'
import { BBS_PHASE_LABELS } from '@/utils/post-labels'
import type { BbsModerationRecord, ModerationAction } from '@/types/bbs'

/**
 * 审核记录时间线：提交、通过、驳回、取消发布等只追加的审计留痕。
 *
 * 与历史版本面板分开——审核和快照解耦：审核不产生「证据快照」，
 * 记录里的版本号只是当时的参考，点它跳不到、也不该跳到快照列表去。
 */
const props = defineProps<{
  postName: string
  mode: 'console' | 'uc'
}>()

const emit = defineEmits<{
  close: []
}>()

const modal = ref<InstanceType<typeof VModal>>()
const loading = ref(true)
const records = ref<BbsModerationRecord[]>([])
/** 加载失败原因；与「真的没有记录」严格分开——空态不能掩盖功能故障 */
const loadError = ref('')

const actionLabels: Record<ModerationAction, string> = {
  SUBMITTED: '提交审核',
  PUBLISHED: '发布',
  APPROVED: '审核通过',
  REJECTED: '审核驳回',
  SUBMISSION_WITHDRAWN: '修改后撤回审核',
  UNPUBLISHED: '取消发布',
}

/** 状态枚举 → 中文（后端存的是 PENDING/PUBLISHED 等枚举名，展示要本地化） */
function phaseLabel(phase?: string) {
  return phase ? BBS_PHASE_LABELS[phase] || phase : '—'
}

/**
 * 最新一条（index 0，列表按时间降序）代表帖子当前状态，节点按结果状态上色；
 * 历史节点保持灰色弱化。未发布无强语义，用主题色兜底。
 */
function dotClass(record: BbsModerationRecord, index: number): string {
  if (index !== 0) {
    return ''
  }
  switch (record.spec.toPhase) {
    case 'PUBLISHED':
      return 'timeline-dot--published'
    case 'REJECTED':
      return 'timeline-dot--rejected'
    case 'PENDING':
      return 'timeline-dot--pending'
    default:
      return 'timeline-dot--draft'
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const api = props.mode === 'console' ? consoleApi : ucApi
    const { data } = await api.listModerationRecords(props.postName)
    records.value = data || []
  } catch (error) {
    // HTTP 错误由全局拦截器提示；这里再把错误打到控制台——空 catch 会把同步异常一并
    // 吞掉且无任何痕迹。
    console.error('[bbs] 审核记录加载失败', error)
    records.value = []
    loadError.value = error instanceof Error ? error.message : '网络请求失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <VModal ref="modal" title="审核记录" :width="720" mount-to-body @close="emit('close')">
    <!--
      稳定容器包住全部动态内容：VModal 内容区被 overlayscrollbars 接管、重排过，
      v-if 切换直接落在内容区顶层会锚点对不上、insertBefore 崩溃——切换必须
      发生在这个稳定 div 内部。
    -->
    <div>
      <VLoading v-if="loading" />
      <!-- 失败态单独占位 + 重试入口：不能伪装成「暂无记录」，否则分不清是没数据还是功能故障 -->
      <VEmpty v-else-if="loadError" title="审核记录加载失败" :message="loadError">
        <template #actions>
          <VButton @click="load">重试</VButton>
        </template>
      </VEmpty>
      <div v-else class="moderation-timeline">
      <!-- 成功但无数据：官方标准空态（标题 + 说明 + 刷新动作） -->
      <VEmpty
        v-if="records.length === 0"
        title="暂无审核记录"
        message="提交审核、通过、驳回或取消发布后会在这里留痕"
      >
        <template #actions>
          <VButton @click="load">刷新</VButton>
        </template>
      </VEmpty>
      <template v-else>
        <article v-for="(record, index) in records" :key="record.metadata.name">
          <span class="timeline-dot" :class="dotClass(record, index)" />
          <div class="timeline-card">
            <div class="timeline-card__head">
              <strong>{{ actionLabels[record.spec.action] || record.spec.action }}</strong>
              <time>{{ formatDate(record.spec.createdAt) }}</time>
            </div>
            <p>操作者：{{ record.spec.actor }}</p>
            <!-- 只呈现该动作落定后的最终状态，不展示来源态（— → 待审核这类过渡对
                 阅读留痕没有增益）；驳回原因等上下文由下方 blockquote 承载 -->
            <p v-if="record.spec.toPhase">状态：{{ phaseLabel(record.spec.toPhase) }}</p>
            <blockquote v-if="record.spec.reason">{{ record.spec.reason }}</blockquote>
          </div>
        </article>
      </template>
      </div>
    </div>

    <template #footer>
      <VButton @click="modal?.close()">关闭</VButton>
    </template>
  </VModal>
</template>

<style scoped>
.moderation-timeline {
  position: relative;
  max-height: 65vh;
  overflow: auto;
  padding: 0.25rem 0.25rem 0.25rem 1rem;
}

.moderation-timeline article {
  position: relative;
  border-left: 2px solid var(--bbs-border);
  padding: 0 0 1rem 1.25rem;
}

.timeline-dot {
  position: absolute;
  top: 0.65rem;
  left: -0.38rem;
  width: 0.65rem;
  height: 0.65rem;
  border: 2px solid var(--bbs-bg-surface);
  border-radius: 50%;
  background: var(--bbs-text-faint);
}

/* 最新一条=帖子当前状态，按结果状态上色（绿=已发布 / 红=驳回 / 橙=待审核 /
   主题色=未发布）；历史节点保持灰色弱化 */
.timeline-dot--published {
  background: var(--bbs-success);
  box-shadow: 0 0 0 1px var(--bbs-success);
}

.timeline-dot--rejected {
  background: var(--bbs-danger);
  box-shadow: 0 0 0 1px var(--bbs-danger);
}

.timeline-dot--pending {
  background: var(--bbs-warning);
  box-shadow: 0 0 0 1px var(--bbs-warning);
}

.timeline-dot--draft {
  background: var(--bbs-accent);
  box-shadow: 0 0 0 1px var(--bbs-accent);
}

.timeline-card {
  border: 1px solid var(--bbs-border);
  border-radius: 0.45rem;
  padding: 0.75rem;
  background: var(--bbs-bg-surface);
}

.timeline-card__head {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.4rem;
}

.timeline-card p {
  margin: 0;
  color: var(--bbs-text-muted);
  font-size: 0.75rem;
}

.timeline-card time {
  color: var(--bbs-text-muted);
  font-size: 0.75rem;
}

.timeline-card blockquote {
  margin: 0.55rem 0 0;
  border-left: 3px solid var(--bbs-danger);
  padding: 0.45rem 0.65rem;
  background: var(--bbs-danger-bg);
  color: var(--bbs-danger);
}
</style>
