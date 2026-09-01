<script lang="ts" setup>
import {
  IconArrowDown,
  VDropdown,
  VEntity,
  VEntityContainer,
  VEntityField,
} from '@halo-dev/components'
import Fuse from 'fuse.js'
import { computed, nextTick, ref, watch } from 'vue'
import type { CategoryVo } from '@/types/bbs'
import CategoryMark from '@/shared/CategoryMark.vue'

/**
 * 分类筛选下拉，对齐官方 `ui/src/components/filter/CategoryFilterDropdown.vue`：
 * 同样用 fuse.js 做模糊搜索、选中项回显在标签上、再次点击取消选择。
 *
 * 适配点：分类数据由父组件传入（BBS 的分类列表已在页面加载时取过，不必再查一次）；
 * 聚焦改原生 DOM；样式改 scoped CSS。
 */
const props = withDefaults(
  defineProps<{
    label: string
    categories: CategoryVo[]
    modelValue?: string
  }>(),
  { modelValue: undefined }
)

const emit = defineEmits<{ (event: 'update:modelValue', value?: string): void }>()

const dropdown = ref()
const keyword = ref('')

let fuse: Fuse<CategoryVo> | undefined = undefined

watch(
  () => props.categories,
  () => {
    fuse = new Fuse(props.categories || [], {
      keys: ['displayName', 'name', 'slug'],
      useExtendedSearch: true,
      threshold: 0.2,
    })
  },
  { immediate: true }
)

const searchResults = computed(() => {
  if (!fuse || !keyword.value) {
    return props.categories
  }
  return fuse.search(keyword.value).map((item) => item.item)
})

const selectedCategory = computed(() =>
  props.categories?.find((category) => category.name === props.modelValue)
)

function handleSelect(category: CategoryVo) {
  emit('update:modelValue', category.name === props.modelValue ? undefined : category.name)
  dropdown.value?.hide()
}

function onDropdownShow() {
  setTimeout(() => {
    nextTick(() => {
      const input = document.getElementById('categoryFilterDropdownInput')
      if (input instanceof HTMLInputElement) {
        input.focus()
      }
    })
  }, 200)
}
</script>

<template>
  <VDropdown ref="dropdown" popper-class="bbs-panel-popper" @show="onDropdownShow">
    <div class="bbs-filter-trigger" :class="{ 'bbs-filter-trigger--active': modelValue !== undefined }">
      <span class="bbs-filter-trigger__label">
        {{ selectedCategory ? `${label}：${selectedCategory.displayName}` : label }}
      </span>
      <IconArrowDown />
    </div>
    <template #popper>
      <div class="bbs-filter-panel">
        <div class="bbs-filter-panel__search">
          <FormKit
            id="categoryFilterDropdownInput"
            v-model="keyword"
            placeholder="搜索"
            type="text"
          />
        </div>
        <VEntityContainer>
          <VEntity
            v-for="category in searchResults"
            :key="category.name"
            :is-selected="modelValue === category.name"
            @click="handleSelect(category)"
          >
            <template #start>
              <VEntityField :description="`/bbs?category=${category.slug}`">
                <template #title>
                  <!-- 层级用「父级弱化前缀」表达：不缩进（两行左缘会对不齐）、
                       也不拼等权重长串 -->
                  <span class="cat-option">
                    <CategoryMark
                      :display-name="category.displayName"
                      :parent-name="category.parent?.displayName"
                      :color="category.color"
                      :icon-svg="category.iconSvg"
                    />
                  </span>
                </template>
              </VEntityField>
            </template>
            <template #end>
              <VEntityField :description="`${category.postCount || 0} 篇`" />
            </template>
          </VEntity>
        </VEntityContainer>
      </div>
    </template>
  </VDropdown>
</template>

<style scoped>
/* bbs-filter-trigger / bbs-filter-panel 系列见 styles/tokens.css */
.cat-option {
  display: inline-flex;
  align-items: center;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--bbs-text, #111827);
}
</style>
