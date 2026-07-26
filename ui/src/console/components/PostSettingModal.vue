<script setup lang="ts">
import { VModal, VButton, VSpace } from '@halo-dev/components'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import type { PostFormState } from '@/types/bbs'

/**
 * 帖子快捷设置弹窗（Console / UC 共用）：仅改 spec 元数据，不触碰正文。
 * managed=false（UC 场景）隐藏类型 / 置顶等管理特权字段。
 * 确认逻辑由父组件提供（confirm 事件）。
 */
const props = withDefaults(
  defineProps<{
    categories: { label: string; value: string }[]
    saving?: boolean
    managed?: boolean
  }>(),
  { saving: false, managed: true }
)

const emit = defineEmits<{ (e: 'close'): void; (e: 'confirm'): void }>()

const form = defineModel<PostFormState>({ required: true })
</script>

<template>
  <VModal title="帖子设置" :width="680" mount-to-body @close="emit('close')">
    <PostSettingForm v-model="form" :categories="props.categories" :managed="props.managed" />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="props.saving" @click="emit('confirm')">保存</VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
