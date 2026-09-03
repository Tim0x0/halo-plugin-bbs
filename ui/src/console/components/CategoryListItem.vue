<script setup lang="ts">
import { computed } from 'vue'
import { utils } from '@halo-dev/ui-shared'
import {
  VDropdown,
  VDropdownItem,
  VStatusDot,
  Dialog,
  Toast,
  IconMore,
} from '@halo-dev/components'
import { categoryApi } from '@/api/bbs'
import type { CategoryVo } from '@/types/bbs'
import CategoryMark from '@/shared/CategoryMark.vue'

/**
 * 分类树项（对齐 Halo 官方文章分类 CategoryListItem）：
 * hover 显示拖拽手柄；右侧帖数 → 创建时间(绝对) → 启用态 → 操作下拉。
 */
const props = defineProps<{ categoryTreeNode: CategoryVo }>()
const emit = defineEmits<{
  (e: 'edit'): void
  (e: 'createByParent'): void
  (e: 'saved'): void
}>()

const category = computed(() => props.categoryTreeNode)

const createdLabel = computed(() =>
  category.value.creationTimestamp
    ? utils.date.format(category.value.creationTimestamp)
    : ''
)

async function onDelete() {
  const count = category.value.postCount || 0
  const childCount = category.value.children?.length || 0
  const parts: string[] = []
  if (count > 0) parts.push(`直属的 ${count} 篇已发布帖子将保留，但会取消分类关联`)
  if (childCount > 0) parts.push(`其 ${childCount} 个子分类将自动升为一级分类`)
  Dialog.warning({
    title: '删除分类',
    description:
      parts.length > 0
        ? `「${category.value.displayName}」${parts.join('；')}。确定删除吗？`
        : `确定删除「${category.value.displayName}」吗？`,
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        await categoryApi.delete(category.value.name)
        Toast.success('已删除')
        emit('saved')
      } catch {
        /* 请求错误由全局拦截器提示 */
      }
    },
  })
}
</script>

<template>
  <div class="category-item group">
    <!-- 拖拽手柄：hover 显示（对齐 Halo 官方） -->
    <div class="drag-element">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
           stroke-width="2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
    </div>
    <div class="category-item__main">
      <div class="category-item__name-row">
        <CategoryMark
          :display-name="category.displayName"
          :color="category.color"
          :icon-svg="category.iconSvg"
          :size="14"
        />
      </div>
      <!-- 对齐官方：列表不展示 description；permalink 用可点前台链 -->
      <a
        class="category-item__link"
        :href="category.permalink || `/bbs?category=${encodeURIComponent(category.slug)}`"
        target="_blank"
        rel="noopener"
        @click.stop
      >
        /bbs?category={{ category.slug }}
      </a>
    </div>
    <div class="category-item__end">
      <!-- 只报本分类直属篇数（对齐官方文章分类）；含子分类的合计放进 tooltip，
           列表不挂「（含子 N）」这种额外文字 -->
      <span
        v-tooltip="
          !category.parentName && (category.totalPostCount || 0) > (category.postCount || 0)
            ? `含子分类共 ${category.totalPostCount} 篇`
            : ''
        "
        class="category-item__count"
      >
        {{ category.postCount || 0 }} 篇
      </span>
      <span v-if="createdLabel" class="category-item__time">{{ createdLabel }}</span>
      <VStatusDot v-if="category.enabled !== false" state="success" text="已启用" />
      <VStatusDot v-else state="default" text="已停用" />
      <VDropdown>
        <div class="category-item__more" @click.stop><IconMore /></div>
        <template #popper>
          <VDropdownItem @click="emit('edit')">编辑</VDropdownItem>
          <VDropdownItem v-if="!category.parentName" @click="emit('createByParent')">
            添加子分类
          </VDropdownItem>
          <VDropdownItem type="danger" @click="onDelete">删除</VDropdownItem>
        </template>
      </VDropdown>
    </div>
  </div>
</template>

<style scoped>
.category-item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  transition: var(--bbs-transition);
}
.category-item:hover {
  background: var(--bbs-bg-hover);
}
.drag-element {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  display: none;
  align-items: center;
  justify-content: center;
  width: 14px;
  cursor: move;
  background: var(--bbs-bg-soft);
  color: var(--bbs-text-muted);
}
.category-item:hover .drag-element,
.category-item.dragging .drag-element {
  display: flex;
}
.category-item__main {
  flex: 1;
  min-width: 0;
}
.category-item__name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.category-item__link {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--bbs-text-faint, #9ca3af);
  max-width: 28rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.category-item__link:hover {
  color: var(--bbs-text-muted, #6b7280);
}
.category-item__end {
  display: flex;
  align-items: center;
  gap: 24px;
  flex: none;
}
.category-item__count {
  font-size: 12px;
  color: var(--bbs-text-muted);
}
.category-item__time {
  font-size: 12px;
  color: var(--bbs-text-muted);
  font-variant-numeric: tabular-nums;
}
.category-item__more {
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  color: var(--bbs-text-muted);
}
.category-item__more:hover {
  background: var(--bbs-bg-soft);
  color: var(--bbs-text);
}
</style>
