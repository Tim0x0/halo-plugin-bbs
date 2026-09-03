<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { VueEditor } from '@halo-dev/richtext-editor'

/**
 * 编辑器大纲页签：标题层级目录，形态对齐官方默认编辑器（DefaultEditor）的 toc 页签——
 * 灰阶列表条目（hover / 选中灰底）、按层级缩进、层级角标（官方用 lucide H 图标，
 * 本项目未装该图标集，用同形态的 H1~H6 文字角标）；无标题时居中「暂无大纲」。
 *
 * 交互比官方只点选更进一步：点击定位光标并平滑滚动；
 * 光标移动时自动高亮最近的上方标题。由 PostSidePanel 作为「大纲」页签渲染。
 */
/** 大纲条目：pos 为 heading 节点在文档中的起始位置 */
interface HeadingItem {
  level: number
  text: string
  pos: number
}

const props = defineProps<{ editor: VueEditor }>()

const headings = ref<HeadingItem[]>([])
const activePos = ref(-1)

/** 文档变化后重建标题列表 */
function rebuild() {
  const { doc } = props.editor.state
  const items: HeadingItem[] = []
  doc.descendants((node, pos) => {
    if (node.type.name === 'heading') {
      const text = node.textContent.trim()
      if (text) {
        items.push({ level: (node.attrs.level as number) || 1, text, pos })
      }
    }
    return true
  })
  headings.value = items
  updateActive()
}

/** 光标位置高亮最近的上方标题（列表中第一个 pos 不晚于光标的项） */
function updateActive() {
  const from = props.editor.state.selection.from
  let active = -1
  for (const item of headings.value) {
    if (item.pos <= from) {
      active = item.pos
    } else {
      break
    }
  }
  activePos.value = active
}

/** 点击跳转：光标定位到标题处（后续输入衔接自然），并平滑滚动对齐到顶部 */
function scrollTo(item: HeadingItem) {
  const node = props.editor.view.nodeDOM(item.pos)
  const el = node instanceof HTMLElement ? node : null
  props.editor.commands.focus(item.pos + 1)
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activePos.value = item.pos
}

onMounted(() => {
  props.editor.on('update', rebuild)
  props.editor.on('selectionUpdate', updateActive)
  rebuild()
})

onBeforeUnmount(() => {
  props.editor.off('update', rebuild)
  props.editor.off('selectionUpdate', updateActive)
})
</script>

<template>
  <ul v-if="headings.length" class="bbs-outline">
    <li
      v-for="item in headings"
      :key="item.pos"
      class="bbs-outline__item"
      :class="{ 'bbs-outline__item--active': item.pos === activePos }"
      :title="item.text"
      @click="scrollTo(item)"
    >
      <div class="bbs-outline__row" :style="{ paddingLeft: `${(item.level - 1) * 0.8}rem` }">
        <span class="bbs-outline__level">H{{ item.level }}</span>
        <span class="bbs-outline__text">{{ item.text }}</span>
      </div>
    </li>
  </ul>
  <div v-else class="bbs-outline__empty">暂无大纲</div>
</template>

<style scoped>
/* 色值逐一对齐官方：条目文字 gray-600、hover/选中底 gray-100、hover 文字 gray-900 */
.bbs-outline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.bbs-outline__item + .bbs-outline__item {
  margin-top: 0.25rem;
}

.bbs-outline__item {
  cursor: pointer;
  overflow: hidden;
  border-radius: var(--bbs-radius);
  padding: 0.25rem 0.375rem;
  color: #4b5563;
  font-size: 0.875rem;
  transition: var(--bbs-transition);
}

.bbs-outline__item:hover {
  background: #f3f4f6;
  color: #111827;
}

.bbs-outline__item--active {
  background: #f3f4f6;
}

.bbs-outline__row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

/* 层级角标：官方为灰底图标，本项目同形态文字版（H1~H6）；
   hover / 选中时角标转白底，与官方 group-hover:bg-white 一致 */
.bbs-outline__level {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 1rem;
  min-width: 1rem;
  border-radius: 2px;
  background: #f3f4f6;
  padding: 0.125rem;
  font-size: 0.625rem;
  font-weight: 600;
  line-height: 1;
  transition: var(--bbs-transition);
}

.bbs-outline__item:hover .bbs-outline__level,
.bbs-outline__item--active .bbs-outline__level {
  background: #fff;
}

.bbs-outline__text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bbs-outline__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2.5rem 0;
  color: #4b5563;
  font-size: 0.875rem;
}
</style>
