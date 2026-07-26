<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  VPageHeader,
  VButton,
  VCard,
  VSpace,
  VEmpty,
  VLoading,
  VEntityContainer,
  VEntity,
  VEntityField,
  VStatusDot,
  VDropdownItem,
  Toast,
  Dialog,
  IconAddCircle,
  IconArrowLeft,
  IconRefreshLine,
} from '@halo-dev/components'
import { Icon } from '@iconify/vue'
import RiFolder2Line from '~icons/ri/folder-2-line'
import { consoleApi, categoryApi } from '@/api/bbs'
import type { CategoryVo } from '@/types/bbs'
import CategoryEditingModal from '@/console/components/CategoryEditingModal.vue'

/**
 * 分类管理页：带已发布帖子数的列表 + 创建/编辑弹窗。
 * 由帖子列表页「分类」按钮进入（对标官方文章的分类管理）。
 */
const loading = ref(false)
const categories = ref<CategoryVo[]>([])
const modalVisible = ref(false)
const editingCategory = ref<CategoryVo | undefined>()

async function fetchCategories() {
  loading.value = true
  try {
    const { data } = await consoleApi.listCategories()
    categories.value = data || []
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    loading.value = false
  }
}

// 拖拽排序（对齐官方分类管理）：拖动行调整顺序，落点后把 priority 回写为行下标
const dragIndex = ref<number | null>(null)
const reordering = ref(false)
const canDrag = computed(() => !loading.value && !reordering.value)

function onDragStart(index: number) {
  dragIndex.value = index
}

function onDragEnd() {
  dragIndex.value = null
}

async function onDrop(targetIndex: number) {
  const from = dragIndex.value
  dragIndex.value = null
  if (from === null || from === targetIndex) {
    return
  }
  const list = categories.value.slice()
  const [moved] = list.splice(from, 1)
  list.splice(targetIndex, 0, moved)
  categories.value = list
  await persistOrder()
}

async function persistOrder() {
  reordering.value = true
  const results = await Promise.allSettled(
    categories.value
      .map((category, index) => ({ category, index }))
      .filter(({ category, index }) => (category.priority ?? 0) !== index)
      .map(({ category, index }) =>
        categoryApi.patch(category.name, [
          { op: 'replace', path: '/spec/priority', value: index },
        ])
      )
  )
  const failed = results.filter((r) => r.status === 'rejected').length
  if (failed === 0) {
    Toast.success('分类顺序已保存')
  } else {
    Toast.warning(`部分排序保存失败（${failed} 项）`)
  }
  reordering.value = false
  await fetchCategories()
}

function openCreate() {
  editingCategory.value = undefined
  modalVisible.value = true
}

function openEdit(category: CategoryVo) {
  editingCategory.value = category
  modalVisible.value = true
}

function onDelete(category: CategoryVo) {
  const count = category.postCount || 0
  Dialog.warning({
    title: '删除分类',
    description:
      count > 0
        ? `「${category.displayName}」下还有 ${count} 篇已发布帖子，删除后这些帖子将变为无分类。确定删除吗？`
        : `确定删除「${category.displayName}」吗？`,
    confirmType: 'danger',
    onConfirm: async () => {
      try {
        await categoryApi.delete(category.name)
        Toast.success('已删除')
        await fetchCategories()
      } catch {
        /* 请求错误由全局拦截器提示 */
      }
    },
  })
}

onMounted(fetchCategories)
</script>

<template>
  <VPageHeader title="BBS 社区分类">
    <template #icon>
      <RiFolder2Line class="header-icon" />
    </template>
    <template #actions>
      <VButton size="sm" :route="{ name: 'BbsPosts' }">
        <template #icon><IconArrowLeft /></template>
        返回帖子列表
      </VButton>
      <VButton size="sm" @click="fetchCategories">
        <template #icon><IconRefreshLine /></template>
        刷新
      </VButton>
      <VButton type="secondary" @click="openCreate">
        <template #icon><IconAddCircle /></template>
        新建分类
      </VButton>
    </template>
  </VPageHeader>

  <div class="page-body">
    <VCard :body-class="['!p-0']">
      <VLoading v-if="loading" />
      <Transition v-else-if="categories.length === 0" appear name="fade">
        <VEmpty title="暂无分类" message="创建分类（如 CS1.6、DNF、插件专区）来组织帖子">
          <template #actions>
            <VButton type="secondary" @click="openCreate">
              <template #icon><IconAddCircle /></template>
              新建分类
            </VButton>
          </template>
        </VEmpty>
      </Transition>
      <Transition v-else appear name="fade">
        <VEntityContainer>
          <VEntity
            v-for="(category, index) in categories"
            :key="category.name"
            class="category-row"
            :class="{ 'category-row--dragging': dragIndex === index }"
            :draggable="canDrag"
            @dragstart="onDragStart(index)"
            @dragover.prevent
            @drop.prevent="onDrop(index)"
            @dragend="onDragEnd"
          >
            <template #start>
              <VEntityField>
                <template #description>
                  <span
                    class="category-tile"
                    :style="{
                      background: (category.color || '#6366f1') + '1a',
                      color: category.color || '#6366f1',
                    }"
                  >
                    <!-- 优先离线 SVG（保存时解析），回退 Iconify 在线组件，最后色点 -->
                    <span
                      v-if="category.iconSvg"
                      class="category-tile__svg"
                      v-html="category.iconSvg"
                    ></span>
                    <Icon
                      v-else-if="category.icon"
                      :icon="category.icon"
                      class="category-tile__icon"
                    />
                    <span
                      v-else
                      class="category-tile__dot"
                      :style="{ background: category.color || '#6366f1' }"
                    ></span>
                  </span>
                </template>
              </VEntityField>
              <VEntityField :title="category.displayName" :description="category.description">
                <template #extra>
                  <span class="category-slug">/{{ category.slug }}</span>
                </template>
              </VEntityField>
            </template>
            <template #end>
              <VEntityField width="6rem">
                <template #description>
                  <span class="category-count">{{ category.postCount || 0 }} 篇帖子</span>
                </template>
              </VEntityField>
              <VEntityField width="5rem">
                <template #description>
                  <VStatusDot
                    v-if="category.enabled !== false"
                    state="success"
                    text="已启用"
                  />
                  <VStatusDot v-else state="default" text="已停用" />
                </template>
              </VEntityField>
            </template>
            <template #dropdownItems>
              <VDropdownItem @click="openEdit(category)">编辑</VDropdownItem>
              <VDropdownItem type="danger" @click="onDelete(category)">删除</VDropdownItem>
            </template>
          </VEntity>
        </VEntityContainer>
      </Transition>
    </VCard>
  </div>

  <CategoryEditingModal
    v-if="modalVisible"
    :category="editingCategory"
    @saved="fetchCategories"
    @close="modalVisible = false"
  />
</template>

<style scoped>
.header-icon {
  margin-right: 0.5rem;
  align-self: center;
}

.page-body {
  margin: 0;
}

@media (min-width: 768px) {
  .page-body {
    margin: 1rem;
  }
}

.category-tile {
  display: inline-grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 8px;
  font-size: 1rem;
}

.category-tile__svg {
  display: inline-flex;
}

.category-tile__svg :deep(svg) {
  width: 1rem;
  height: 1rem;
}

.category-tile__icon {
  width: 1rem;
  height: 1rem;
}

.category-tile__dot {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 4px;
}

.category-slug {
  margin-left: 0.375rem;
  font-size: 0.75rem;
  color: var(--bbs-text-faint);
}

.category-count {
  font-size: 0.75rem;
  color: var(--bbs-text-muted);
}

.category-row {
  cursor: grab;
}

.category-row--dragging {
  opacity: 0.5;
}
</style>
