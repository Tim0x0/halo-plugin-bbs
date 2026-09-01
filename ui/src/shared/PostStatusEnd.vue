<script setup lang="ts">
/**
 * Console/UC 帖子行 #end 发布状态列
 *
 * 设计语言对齐官方文章列表（PublishStatusField）：状态列只有纯文字，表达主状态——
 * 已发布 / 未发布 / 待审核 / 已驳回。官方绿点的语义是「有未发布的修改」，不能拿来
 * 表示「已发布」，故本列不出现任何状态点。
 *
 * 已发布帖的未发布修改状态（修改未提交 / 修改待审 / 修改驳回）由标题侧的小点
 * 表达（PostEntityStart，官方 inProgress 的位置），本列不参与——两条通道各司其职，
 * 版主不会把「已发布但改了没提交」误读成别的东西。
 *
 * 其余标记各有落点，不往这一列塞（塞进来会让列宽随行变化、下游的时间列跟着错位）：
 * 置顶 / 已解决走标题行图标组（PostEntityStart），
 * 锁定 / 关闭回复走 #end 的可点开关列（PostToggleFields）。
 */
import { VEntityField } from '@halo-dev/components'
import type { BbsPostVo } from '@/types/bbs'
import { BBS_PHASE_LABELS } from '@/utils/post-labels'

defineProps<{ post: BbsPostVo }>()
</script>

<template>
  <VEntityField width="5.5rem">
    <template #description>
      <!-- 驳回原因不占行面：悬停可见，完整留痕在审核记录弹窗 -->
      <span
        v-tooltip="
          post.phase === 'REJECTED' && post.rejectReason
            ? `驳回原因：${post.rejectReason}`
            : ''
        "
        class="entity-status-text"
      >
        {{ BBS_PHASE_LABELS[post.phase] || '未发布' }}
      </span>
    </template>
  </VEntityField>
</template>

<style scoped>
/* .entity-end 是 flex 容器，成员默认可压缩——列窄时固定宽度会被压掉、行与行
   之间又错开。这里锁死不参与压缩，状态列才能列列对齐（该选择器命中的是本组件
   根节点 VEntityField 的根元素，scoped 会带上本组件的 scope id）。 */
.entity-field-wrapper {
  flex: none;
}

/* 纯文字状态：字号 / 颜色对齐 VEntityField description 的观感（同列表时间列） */
.entity-status-text {
  font-size: 0.75rem;
  color: var(--bbs-text-muted, #6b7280);
}
</style>
