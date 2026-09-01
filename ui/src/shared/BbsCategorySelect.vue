<script setup lang="ts">
/**
 * BBS 分类选择器（单选）。
 *
 * 替换原生 `<select>`：原生下拉既没法画色点/图标，也没法把父级做成弱化前缀，
 * 用不换行空格凑缩进更是硬凑。
 *
 * 骨架照 Halo 官方 `MenuItemParentSelect.vue`（菜单父级选择）——同样是「单选 +
 * 只读触发器 + 手动控制的 VDropdown + onClickOutside + auto-size 面板宽度跟随」。
 * **没有**照 Halo 的 `CategorySelect`：那份是文章多分类场景（多选、现场建分类、
 * 键盘上下选），且重度依赖 app 级的 FormKit 主题类，插件取不到。
 *
 * 为什么不做成 FormKit 自定义输入：插件的依赖里没有 @formkit/*（宿主提供），
 * 写 input 定义需要 @formkit/inputs 的 section 构建器，装它会打进第二份 FormKit
 * registry（见 PostSettingForm 里同样的取舍）。所以这里是个普通组件，由调用方
 * 塞进 FormKit 的 `#input` 段插槽，label / help / 校验报错仍走 FormKit。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { onClickOutside, useResizeObserver } from '@vueuse/core'
import { IconArrowDown, IconClose, VDropdown } from '@halo-dev/components'
import Fuse from 'fuse.js'
import type { CategoryVo } from '@/types/bbs'
import CategoryMark from '@/shared/CategoryMark.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    categories: CategoryVo[]
    placeholder?: string
    /** 允许清空（批量设置分类场景：留空 = 清除分类） */
    clearable?: boolean
    disabled?: boolean
  }>(),
  {
    modelValue: '',
    placeholder: '选择分类',
    clearable: false,
    disabled: false,
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const visible = ref(false)
const keyword = ref('')
const wrapperRef = ref<HTMLElement>()
const popperRef = ref<HTMLElement>()

// 容器尺寸变化时让 floating-vue 重算宽度（auto-size 跟随触发器宽度）
useResizeObserver(wrapperRef, () => {
  window.dispatchEvent(new Event('resize'))
})

onClickOutside(
  wrapperRef,
  () => {
    visible.value = false
  },
  { ignore: [popperRef] }
)

const selected = computed(() => props.categories.find((c) => c.name === props.modelValue))

let fuse: Fuse<CategoryVo> | undefined
watch(
  () => props.categories,
  () => {
    fuse = new Fuse(props.categories || [], {
      keys: ['displayName', 'slug', 'name'],
      useExtendedSearch: true,
      threshold: 0.2,
    })
  },
  { immediate: true }
)

const results = computed(() => {
  if (!fuse || !keyword.value.trim()) {
    return props.categories
  }
  return fuse.search(keyword.value.trim()).map((item) => item.item)
})

function toggle() {
  if (props.disabled) {
    return
  }
  visible.value = !visible.value
  if (visible.value) {
    keyword.value = ''
    // 等浮层挂载完再聚焦；取不到元素静默跳过
    setTimeout(() => {
      nextTick(() => {
        const input = popperRef.value?.querySelector('input')
        if (input instanceof HTMLInputElement) {
          input.focus()
        }
      })
    }, 120)
  }
}

function select(category: CategoryVo) {
  emit('update:modelValue', category.name)
  visible.value = false
}

function clear() {
  emit('update:modelValue', '')
  visible.value = false
}
</script>

<template>
  <VDropdown
    :triggers="[]"
    :shown="visible"
    auto-size
    :auto-hide="false"
    container="body"
    :distance="6"
    class="cat-select"
    popper-class="bbs-panel-popper"
  >
    <div
      ref="wrapperRef"
      class="cat-select__trigger"
      :class="{ 'cat-select__trigger--disabled': disabled }"
      role="combobox"
      :aria-expanded="visible"
      tabindex="0"
      @click="toggle"
      @keydown.enter.prevent="toggle"
      @keydown.space.prevent="toggle"
    >
      <CategoryMark
        v-if="selected"
        :display-name="selected.displayName"
        :parent-name="selected.parent?.displayName"
        :color="selected.color"
        :icon-svg="selected.iconSvg"
      />
      <span v-else class="cat-select__placeholder">{{ placeholder }}</span>

      <span class="cat-select__actions">
        <IconClose
          v-if="clearable && selected"
          v-tooltip="'清除'"
          class="cat-select__clear"
          @click.stop="clear"
        />
        <IconArrowDown class="cat-select__caret" :class="{ 'is-open': visible }" />
      </span>
    </div>

    <template #popper>
      <div ref="popperRef" class="cat-panel">
        <div class="cat-panel__search">
          <input v-model="keyword" type="text" placeholder="搜索分类" />
        </div>
        <ul class="cat-panel__list">
          <li v-if="!results.length" class="cat-panel__empty">没有匹配的分类</li>
          <li
            v-for="category in results"
            :key="category.name"
            class="cat-panel__item"
            :class="{ 'is-selected': category.name === modelValue }"
            @click="select(category)"
          >
            <CategoryMark
              :display-name="category.displayName"
              :parent-name="category.parent?.displayName"
              :color="category.color"
              :icon-svg="category.iconSvg"
            />
            <span class="cat-panel__count">{{ category.postCount || 0 }} 篇</span>
          </li>
        </ul>
      </div>
    </template>
  </VDropdown>
</template>

<style scoped>
.cat-select {
  width: 100%;
}

/* 触发器：撑满 FormKit 的 inner 边框盒，高度与其它输入框一致 */
.cat-select__trigger {
  display: flex;
  width: 100%;
  min-height: 2.25rem;
  align-items: center;
  gap: 0.5rem;
  padding: 0 0.75rem;
  cursor: pointer;
  font-size: 0.875rem;
  color: var(--bbs-text, #111827);
  outline: none;
}

.cat-select__trigger--disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.cat-select__placeholder {
  flex: 1;
  color: var(--bbs-text-faint, #9ca3af);
}

.cat-select__actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  flex: none;
}

.cat-select__clear,
.cat-select__caret {
  width: 1rem;
  height: 1rem;
  color: var(--bbs-text-faint, #9ca3af);
}

.cat-select__clear:hover {
  color: var(--bbs-text, #111827);
}

.cat-select__caret {
  transition: transform 150ms ease;
}

.cat-select__caret.is-open {
  transform: rotate(180deg);
}

.cat-panel {
  max-height: 20rem;
  overflow: auto;
  background: #fff;
}

.cat-panel__search {
  position: sticky;
  top: 0;
  background: #fff;
  padding: 0.5rem;
  border-bottom: 1px solid var(--bbs-border, #eaecf0);
}

.cat-panel__search input {
  width: 100%;
  border: 1px solid var(--bbs-border, #eaecf0);
  border-radius: var(--bbs-radius);
  padding: 0.375rem 0.5rem;
  font-size: 0.875rem;
  outline: none;
}

.cat-panel__search input:focus {
  border-color: var(--bbs-accent);
}

.cat-panel__list {
  margin: 0;
  padding: 0.25rem;
  list-style: none;
}

.cat-panel__item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem;
  border-radius: var(--bbs-radius);
  cursor: pointer;
  font-size: 0.875rem;
}

.cat-panel__item:hover {
  background: var(--bbs-bg-hover, #f9fafb);
}

.cat-panel__item.is-selected {
  background: var(--bbs-bg-selected, #f3f4f6);
  font-weight: 500;
}

.cat-panel__count {
  margin-left: auto;
  flex: none;
  font-size: 0.75rem;
  color: var(--bbs-text-faint, #9ca3af);
  font-variant-numeric: tabular-nums;
}

.cat-panel__empty {
  padding: 1.5rem 0.5rem;
  text-align: center;
  font-size: 0.75rem;
  color: var(--bbs-text-faint, #9ca3af);
}
</style>
