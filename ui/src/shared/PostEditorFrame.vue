<script setup lang="ts">
import { shallowRef, onMounted, onBeforeUnmount, watch } from 'vue'
import { VPageHeader } from '@halo-dev/components'
import { RichTextEditor, VueEditor, ExtensionsKit } from '@halo-dev/richtext-editor'
import type { Attachment } from '@halo-dev/api-client'
import type { AxiosRequestConfig } from 'axios'
import PostSidePanel from '@/shared/PostSidePanel.vue'

/**
 * 帖子编辑器骨架，对标 Halo 官方文章编辑器：顶部 VPageHeader（操作按钮由父组件
 * 透传）+ 全屏富文本，标题作为原生 input 注入 RichTextEditor 的 #content 槽，
 * 右侧 #extra 槽挂「大纲 / 详情」边栏（>=640px 显示，窄屏自动隐藏）。
 * 详情内容（帖子只读信息）由父组件经 #details 槽传入。
 *
 * Console / UC 两个发帖页共用本组件，仅图片上传实现与设置字段不同。
 */
const props = defineProps<{
  pageTitle: string
  uploadImage: (file: File, options?: AxiosRequestConfig) => Promise<Attachment>
}>()

const emit = defineEmits<{
  update: []
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
      // 对齐 Halo 官方编辑器：只有编辑器真实更新才通知父级写本地缓存。
      emit('update')
    },
  })
})

onBeforeUnmount(() => {
  editor.value?.destroy()
})

// 父组件异步加载已有帖子正文后会更新 raw，此时把内容灌进编辑器。
watch(raw, (val) => {
  if (editor.value && val !== editor.value.getHTML()) {
    // Tiptap 3 的 setContent 默认 emitUpdate=true。服务端加载和缓存恢复都属于
    // 程序性回填，不能再次伪装成用户输入，否则会触发缓存和自动保存。
    editor.value.commands.setContent(val || '', { emitUpdate: false })
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
            @input="emit('update')"
            @keydown.enter="focusEditor"
          />
        </template>
        <template #extra>
          <PostSidePanel :editor="editor">
            <template #details>
              <slot name="details" />
            </template>
          </PostSidePanel>
        </template>
      </RichTextEditor>
    </div>
  </div>
</template>

<style scoped>
.bbs-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
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
