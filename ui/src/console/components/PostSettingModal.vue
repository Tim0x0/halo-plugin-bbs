<script setup lang="ts">
import { ref } from 'vue'
import { VModal, VButton, VSpace } from '@halo-dev/components'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import type { CategoryVo, PostFormState } from '@/types/bbs'

/**
 * 帖子快捷设置弹窗（Console / UC 共用）：改标题与 spec 元数据，不触碰正文。
 * managed=false（UC 场景）隐藏类型 / 置顶等管理特权字段。
 *
 * 页脚对齐官方文章设置弹窗：主操作按钮 + 保存并列。主操作按帖子状态切换
 * （由父组件决定）：Console 待审核 / 已驳回=通过、纯草稿=发布；
 * UC 未发布 / 已驳回=提交、已发布帖有未提交修改=提交修改；无主操作时只剩保存。
 * 主操作先走同一套表单校验，校验通过才保存并执行。
 */
const props = withDefaults(
  defineProps<{
    categories: CategoryVo[]
    saving?: boolean
    /** 主操作进行中（发布 / 通过审核 / 提交） */
    publishing?: boolean
    managed?: boolean
    /** 当前编辑帖子的 metadata.name——别名唯一性校验时排除自身 */
    postName?: string
    /** 主操作类型；无值则不显示主操作按钮 */
    primaryAction?: 'approve' | 'publish' | 'submit'
    /** 主操作文案：发布 / 通过 / 提交 / 提交修改 */
    primaryLabel?: string
  }>(),
  {
    saving: false,
    publishing: false,
    managed: true,
    postName: undefined,
    primaryAction: undefined,
    primaryLabel: '发布',
  }
)

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'confirm'): void
  (e: 'primary'): void
}>()

const form = defineModel<PostFormState>({ required: true })

const settingForm = ref<InstanceType<typeof PostSettingForm>>()
const submitKind = ref<'save' | 'primary'>('save')
</script>

<template>
  <VModal title="帖子设置" :width="680" mount-to-body @close="emit('close')">
    <PostSettingForm
      ref="settingForm"
      v-model="form"
      :categories="props.categories"
      :managed="props.managed"
      :post-name="props.postName"
      @submit="submitKind === 'primary' ? emit('primary') : emit('confirm')"
    />
    <template #footer>
      <VSpace>
        <VButton
          v-if="props.primaryAction"
          type="secondary"
          :loading="props.publishing"
          @click="submitKind = 'primary'; settingForm?.submit()"
        >
          {{ props.primaryLabel }}
        </VButton>
        <VButton
          type="secondary"
          :loading="props.saving"
          @click="submitKind = 'save'; settingForm?.submit()"
        >
          保存
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
