<script setup lang="ts">
/**
 * Console/UC 帖子行 #end 评论数列。
 *
 * 独立成列（不再挤在标题下的灰字流里）：数字右对齐前的等宽数字 + 固定列宽，
 * 保证多行之间数字位置一致。
 *
 * 对齐官方文章列表的评论语义：计数为官方口径评论总数；存在待审核评论时
 * 图标与数字以警示色上色，并可点击打开评论管理弹窗（仅 Console 列表挂点击）。
 */
import { VEntityField } from '@halo-dev/components'
import RiChat1Line from '~icons/ri/chat-1-line'

const props = defineProps<{
  /** 展示的评论数（Console=官方口径总数；UC=公开可见数） */
  count?: number
  /** 待审核评论数；>0 上色 */
  pending?: number
  /** 可点击（打开评论管理弹窗） */
  clickable?: boolean
}>()

const emit = defineEmits<{ open: [] }>()

function onClick() {
  if (props.clickable) {
    emit('open')
  }
}
</script>

<template>
  <VEntityField width="4rem">
    <template #description>
      <span
        v-tooltip="
          pending
            ? `${count ?? 0} 条评论，${pending} 条待审核`
            : `${count ?? 0} 条评论`
        "
        class="entity-comments"
        :class="{
          'entity-comments--pending': pending,
          'entity-comments--clickable': clickable,
        }"
        @click="onClick"
      >
        <RiChat1Line class="entity-comments__icon" />
        {{ count ?? 0 }}
      </span>
    </template>
  </VEntityField>
</template>

<style scoped>
/* 同 PostStatusEnd：锁死不参与 flex 压缩，保证列宽跨行一致 */
.entity-field-wrapper {
  flex: none;
}

.entity-comments {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.75rem;
  color: var(--bbs-text-muted, #6b7280);
  font-variant-numeric: tabular-nums;
}

.entity-comments__icon {
  width: 0.875rem;
  height: 0.875rem;
  color: var(--bbs-text-faint, #9ca3af);
}

/* 有待审核评论时上色提示处理（警示色 = Halo 的待审核语义） */
.entity-comments--pending,
.entity-comments--pending .entity-comments__icon {
  color: var(--bbs-warning, #d97706);
}

.entity-comments--clickable {
  cursor: pointer;
}

.entity-comments--clickable:hover {
  opacity: 0.8;
}
</style>
