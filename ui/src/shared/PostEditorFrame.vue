<script setup lang="ts">
import { shallowRef, onMounted, onBeforeUnmount, watch } from 'vue'
import { VPageHeader } from '@halo-dev/components'
import { RichTextEditor, VueEditor, ExtensionsKit } from '@halo-dev/richtext-editor'
import type { Attachment } from '@halo-dev/api-client'
import type { AxiosRequestConfig } from 'axios'

/**
 * 帖子编辑器骨架，对标 Halo 官方文章编辑器：顶部 VPageHeader（操作按钮由父组件
 * 透传）+ 全屏富文本，标题作为原生 input 注入 RichTextEditor 的 #content 槽。
 *
 * Console / UC 两个发帖页共用本组件，仅图片上传实现与设置字段不同。
 */
const props = defineProps<{
  pageTitle: string
  uploadImage: (file: File, options?: AxiosRequestConfig) => Promise<Attachment>
}>()

const title = defineModel<string>('title', { default: '' })
const raw = defineModel<string>('raw', { default: '' })

const editor = shallowRef<VueEditor>()

function handleUpload(file: File, options?: AxiosRequestConfig) {
  return props.uploadImage(file, options)
}

function focusEditor() {
  editor.value?.commands.focus('start')
}

onMounted(() => {
  editor.value = new VueEditor({
    content: raw.value || '',
    extensions: [
      ExtensionsKit.configure({
        image: { uploadImage: handleUpload },
        gallery: { uploadImage: handleUpload },
        video: { uploadVideo: handleUpload },
        audio: { uploadAudio: handleUpload },
        // 正文区占位提示：默认是英文，这里覆盖为中文（对标 Halo 官方 DefaultEditor）
        placeholder: { placeholder: '从这里开始写下正文…' },
      }),
    ],
    parseOptions: { preserveWhitespace: true },
    onUpdate: () => {
      raw.value = editor.value?.getHTML() || ''
    },
  })
})

onBeforeUnmount(() => {
  editor.value?.destroy()
})

// 父组件异步加载已有帖子正文后会更新 raw，此时把内容灌进编辑器。
watch(raw, (val) => {
  if (editor.value && val !== editor.value.getHTML()) {
    editor.value.commands.setContent(val || '')
  }
})
</script>

<template>
  <div class="bbs-editor">
    <VPageHeader :title="pageTitle">
      <template #icon>
        <slot name="icon" />
      </template>
      <template #actions>
        <slot name="actions" />
      </template>
    </VPageHeader>

    <div class="bbs-editor__container">
      <RichTextEditor v-if="editor" :editor="editor" class="bbs-editor__rich">
        <template #content>
          <input
            v-model="title"
            type="text"
            placeholder="请输入标题…"
            class="bbs-editor__title"
            @keydown.enter="focusEditor"
          />
        </template>
      </RichTextEditor>
    </div>
  </div>
</template>

<style scoped>
.bbs-editor {
  display: flex;
  flex-direction: column;
}

.bbs-editor__container {
  height: calc(100vh - 3.5rem);
  border-top: 1px solid var(--bbs-border);
  overflow: hidden;
}

.bbs-editor__container :deep(.bbs-editor__rich) {
  height: 100%;
}

.bbs-editor__title {
  width: 100%;
  border: none;
  border-bottom: 1px solid var(--bbs-border);
  outline: none;
  padding: 0.5rem 0;
  margin-bottom: 0.5rem;
  font-size: 2.25rem;
  font-weight: 600;
  line-height: 1.1;
  color: var(--bbs-text);
}

.bbs-editor__title::placeholder {
  color: var(--bbs-text-faint);
}
</style>
