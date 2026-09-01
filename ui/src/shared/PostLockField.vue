<script setup lang="ts">
/**
 * Console/UC 帖子行 #end 的「锁定」列。
 *
 * 形态对齐 Halo 官方文章列表的「可见性」列（`VisibleField.vue`）：
 * **两种状态都显示图标**（列永远非空，宽度稳定、可扫读）、默认态弱化灰、
 * 受限态语义色、Console 下点一下直接切换，不用进设置弹窗。
 *
 * 为什么它不在标题行的图标组里：置顶 / 已解决是内容属性（只读展示），
 * 锁定是管理员巡检时最常改的开关，独立成列才有「一眼扫一列、点一下就改」的价值。
 *
 * readonly=true（UC）时只展示不可点——锁定是管理员权限。
 */
import { computed } from 'vue'
import { VEntityField } from '@halo-dev/components'
import RiLockLine from '~icons/ri/lock-line'
import RiLockUnlockLine from '~icons/ri/lock-unlock-line'
import type { BbsPostVo } from '@/types/bbs'

const props = withDefaults(defineProps<{ post: BbsPostVo; readonly?: boolean }>(), {
  readonly: false,
})

const emit = defineEmits<{ (e: 'toggleLock'): void }>()

const locked = computed(() => !!props.post.locked)

const tip = computed(() => {
  if (locked.value) {
    return props.readonly ? '已锁定：禁止回复，且不能编辑或删除' : '已锁定，点击解锁'
  }
  return props.readonly ? '未锁定' : '未锁定，点击锁定'
})

function onToggle() {
  if (!props.readonly) {
    emit('toggleLock')
  }
}
</script>

<template>
  <VEntityField width="2rem">
    <template #description>
      <component
        :is="locked ? RiLockLine : RiLockUnlockLine"
        v-tooltip="tip"
        class="lock-icon"
        :class="{ 'lock-icon--on': locked, 'lock-icon--clickable': !readonly }"
        @click.stop="onToggle"
      />
    </template>
  </VEntityField>
</template>

<style scoped>
/* 同 PostStatusEnd：锁死不参与 flex 压缩，保证列宽跨行一致 */
.entity-field-wrapper {
  flex: none;
}

.lock-icon {
  width: 1rem;
  height: 1rem;
  color: var(--bbs-text-faint, #9ca3af);
  transition: color 150ms ease;
}

/* 受限态：用语义色点出来，扫一列就知道哪些帖被锁了 */
.lock-icon--on {
  color: var(--bbs-warning, #d97706);
}

.lock-icon--clickable {
  cursor: pointer;
}

.lock-icon--clickable:hover {
  color: var(--bbs-text, #111827);
}
</style>
