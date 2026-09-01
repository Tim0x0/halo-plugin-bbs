<script setup lang="ts">
import { ref } from 'vue'
import { VModal, VButton, VSpace } from '@halo-dev/components'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import type { CategoryVo, PostFormState } from '@/types/bbs'

/**
 * 帖子快捷设置弹窗（Console / UC 共用）：改标题与 spec 元数据，不触碰正文。
 * managed=false（UC 场景）隐藏类型 / 置顶等管理特权字段。
 * 确认逻辑由父组件提供（confirm 事件）——但只有表单校验通过才会触发。
 */
const props = withDefaults(
  defineProps<{
    categories: CategoryVo[]
    saving?: boolean
    managed?: boolean
    /** 当前编辑帖子的 metadata.name——别名唯一性校验时排除自身 */
    postName?: string
  }>(),
  { saving: false, managed: true, postName: undefined }
)

const emit = defineEmits<{ (e: 'close'): void; (e: 'confirm'): void }>()

const form = defineModel<PostFormState>({ required: true })

// 保存按钮走表单提交流程：校验不通过就不会 emit('confirm')
const settingForm = ref<InstanceType<typeof PostSettingForm>>()
</script>

<template>
  <VModal title="帖子设置" :width="680" mount-to-body @close="emit('close')">
    <PostSettingForm
      ref="settingForm"
      v-model="form"
      :categories="props.categories"
      :managed="props.managed"
      :post-name="props.postName"
      @submit="emit('confirm')"
    />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="props.saving" @click="settingForm?.submit()">
          保存
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

