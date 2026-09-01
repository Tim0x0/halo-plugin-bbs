<script setup lang="ts">
import { ref } from 'vue'
import { VTabs, VTabItem } from '@halo-dev/components'
import type { VueEditor } from '@halo-dev/richtext-editor'
import { OverlayScrollbarsComponent } from 'overlayscrollbars-vue'
import 'overlayscrollbars/overlayscrollbars.css'
import PostOutlinePanel from '@/shared/PostOutlinePanel.vue'

/**
 * 编辑器右侧边栏：对齐官方默认编辑器（DefaultEditor）的 #extra 形态——
 * OverlayScrollbarsComponent（autoHide:'scroll'，能滚但不挂原生滚动条）包裹
 * VTabs type="outline" 两个页签：「大纲」（标题目录）与「详情」
 * （帖子只读信息卡片，内容由页面经 #details 槽传入，Console / UC 数据源不同）。
 * 页签 id（toc / information）沿用官方。
 */
defineProps<{ editor: VueEditor }>()

const activeTab = ref<'toc' | 'information'>('toc')
</script>

<template>
  <OverlayScrollbarsComponent
    element="div"
    class="bbs-side-panel"
    :options="{ scrollbars: { autoHide: 'scroll' } }"
    defer
  >
    <VTabs v-model:active-id="activeTab" type="outline">
      <VTabItem id="toc" label="大纲">
        <PostOutlinePanel :editor="editor" />
      </VTabItem>
      <VTabItem id="information" label="详情">
        <slot name="details" />
      </VTabItem>
    </VTabs>
  </OverlayScrollbarsComponent>
</template>

<style scoped>
/* 整栏一个滚动区（页签头随之滚动），内边距与官方 p-2 一致 */
.bbs-side-panel {
  height: 100%;
  background: var(--bbs-bg-surface);
  border-left: 1px solid var(--bbs-border);
  padding: 0.5rem;
}
</style>
