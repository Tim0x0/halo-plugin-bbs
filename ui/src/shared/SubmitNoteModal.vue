<script lang="ts" setup>
/**
 * 提交审核附言弹窗：与驳回弹窗同范式（VModal + FormKit textarea，可留空）。
 * 附言记入 SUBMITTED 审核记录，审核人在「审核记录」里看，不落在帖子本体。
 * 每次 v-if 重建即清空输入，调用方无需重置。
 */
import { ref } from 'vue'
import { VButton, VModal, VSpace } from '@halo-dev/components'

const props = defineProps<{ saving?: boolean }>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'confirm', note: string): void
}>()

const note = ref('')
</script>

<template>
  <VModal title="提交审核" :width="500" mount-to-body @close="emit('close')">
    <FormKit
      v-model="note"
      type="textarea"
      label="给审核人的说明"
      :rows="3"
      help="记入审核记录的提交事件，审核人可见，可留空"
    />
    <template #footer>
      <VSpace>
        <VButton
          type="primary"
          :loading="props.saving"
          @click="emit('confirm', note.trim())"
        >
          提交
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
