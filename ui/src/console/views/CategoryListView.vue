<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Draggable } from '@he-tree/vue'
import '@he-tree/vue/style/default.css'
import {
  VPageHeader,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  Toast,
  IconAddCircle,
} from '@halo-dev/components'
import RiFolder2Line from '~icons/ri/folder-2-line'
import { consoleApi } from '@/api/bbs'
import type { CategoryVo } from '@/types/bbs'
import CategoryListItem from '@/console/components/CategoryListItem.vue'
import CategoryEditingModal from '@/console/components/CategoryEditingModal.vue'

/**
 * 分类管理页（对标 Halo 官方 CategoryList）：
 * @he-tree/vue Draggable 渲染树 + CategoryListItem 项组件。
 * 后端 assembleCategoryVos 返回树序平铺，一级 vo.children 已内嵌子分类。
 *
 * 拖拽后对比拖拽前后的树算出「哪个节点移到了哪」，调一次位置 API 由服务端
 * 重排（对齐官方 updateCategoryPosition），不逐个回写 priority。这样才支持
 * 跨层拖拽换父级，也不会因部分请求失败留下顺序空洞。
 */
const loading = ref(false)
const categories = ref<CategoryVo[]>([])
const categoriesTree = ref<CategoryVo[]>([])
const positionUpdating = ref(false)
const modalVisible = ref(false)
const editingCategory = ref<CategoryVo | undefined>()
const creatingByParent = ref<CategoryVo | undefined>()

/** 节点在树中的位置：父级 name（根为 ''）+ 同级下标 */
interface TreePosition {
  parent: string
  index: number
}

/**
 * 拖拽前的位置快照。存纯数据而非节点引用——he-tree 会就地 mutate 树数组，
 * 存引用的话「拖拽前」会跟着一起变，对比不出差异。
 */
const positionSnapshot = ref<Map<string, TreePosition>>(new Map())

function flattenPositions(nodes: CategoryVo[], parent = ''): Map<string, TreePosition> {
  const result = new Map<string, TreePosition>()
  const walk = (list: CategoryVo[], parentName: string) => {
    list.forEach((node, index) => {
      result.set(node.name, { parent: parentName, index })
      if (node.children?.length) {
        walk(node.children, node.name)
      }
    })
  }
  walk(nodes, parent)
  return result
}

/** 找某节点在当前树中的后继兄弟（用作 beforeName）；已是末位则返回 null */
function nextSiblingOf(nodes: CategoryVo[], name: string): string | null {
  const findIn = (list: CategoryVo[]): string | null | undefined => {
    const index = list.findIndex((n) => n.name === name)
    if (index >= 0) {
      return index + 1 < list.length ? list[index + 1].name : null
    }
    for (const node of list) {
      if (node.children?.length) {
        const found = findIn(node.children)
        if (found !== undefined) {
          return found
        }
      }
    }
    return undefined
  }
  return findIn(nodes) ?? null
}

/**
 * 从拖拽前后的位置对比中算出被移动的节点——一次拖拽只动一个节点。
 *
 * 换父的节点优先（跨层拖拽意图明确）；同父内重排时取「位移最大」的那个：
 * 被移动的元素位移 k，被它挤开的元素各只位移 1。k=1 的相邻交换两者位移相同，
 * 但此时「把 A 移到 B 后」与「把 B 移到 A 前」结果一致，取谁都对。
 */
function buildMoveRequest(
  before: Map<string, TreePosition>,
  afterTree: CategoryVo[]
): { name: string; parentName: string | null; beforeName: string | null } | null {
  const after = flattenPositions(afterTree)

  let moved: string | undefined
  for (const [name, position] of after) {
    const previous = before.get(name)
    if (previous && previous.parent !== position.parent) {
      moved = name
      break
    }
  }

  if (!moved) {
    let maxDelta = 0
    for (const [name, position] of after) {
      const previous = before.get(name)
      if (!previous) {
        continue
      }
      const delta = Math.abs(position.index - previous.index)
      if (delta > maxDelta) {
        maxDelta = delta
        moved = name
      }
    }
  }

  if (!moved) {
    return null
  }
  return {
    name: moved,
    parentName: after.get(moved)?.parent || null,
    beforeName: nextSiblingOf(afterTree, moved),
  }
}

async function fetchCategories() {
  loading.value = true
  try {
    const { data } = await consoleApi.listCategories()
    applyCategories(data || [])
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    loading.value = false
  }
}

/** 用服务端返回的树序平铺列表刷新本地树，并重建位置快照 */
function applyCategories(list: CategoryVo[]) {
  categories.value = list
  // 统一补 children 空数组：后端只给一级分类填 children，而 he-tree 拖拽时会往目标
  // 节点的 children 里插入——落到 children 为 undefined 的子分类上会直接报错。
  // 层级是否合法由后端校验，这里只保证拖拽过程不崩。
  const withChildren = (node: CategoryVo): CategoryVo => ({
    ...node,
    children: (node.children || []).map(withChildren),
  })
  categoriesTree.value = list.filter((c) => !c.parentName).map(withChildren)
  positionSnapshot.value = flattenPositions(categoriesTree.value)
}

async function handleUpdatePosition() {
  if (positionUpdating.value) {
    return
  }
  const request = buildMoveRequest(positionSnapshot.value, categoriesTree.value)
  if (!request) {
    // 拖回原位，无需请求
    positionSnapshot.value = flattenPositions(categoriesTree.value)
    return
  }
  positionUpdating.value = true
  try {
    const { data } = await consoleApi.moveCategory(request.name, {
      parentName: request.parentName,
      beforeName: request.beforeName,
    })
    applyCategories(data || [])
    Toast.success('分类顺序已保存')
  } catch {
    // 请求错误由全局拦截器提示（如超出两级层级限制）；回拉服务端顺序回滚本地树
    await fetchCategories()
  } finally {
    positionUpdating.value = false
  }
}

function openCreate() {
  editingCategory.value = undefined
  creatingByParent.value = undefined
  modalVisible.value = true
}
function openEdit(category: CategoryVo) {
  editingCategory.value = category
  creatingByParent.value = undefined
  modalVisible.value = true
}
function openCreateByParent(parent: CategoryVo) {
  editingCategory.value = undefined
  creatingByParent.value = parent
  modalVisible.value = true
}

onMounted(fetchCategories)
</script>

<template>
  <VPageHeader title="帖子分类">
    <template #icon>
      <RiFolder2Line class="bbs-header-icon" />
    </template>
    <template #actions>
      <!-- 返回帖子列表：与回收站页的返回入口完全一致（默认色 + 文案「返回」），
           两处都是退出动作，强调色留给「新建分类」这类主动作 -->
      <VButton size="sm" :route="{ name: 'BbsPosts' }">返回</VButton>
      <VButton type="secondary" @click="openCreate">
        <template #icon><IconAddCircle /></template>
        新建分类
      </VButton>
    </template>
  </VPageHeader>

  <div class="bbs-page-body">
    <VCard :body-class="['!p-0']">
      <!-- header 对齐官方分类页：只报数量，返回靠路由、刷新靠拖拽后的自动回写 -->
      <template #header>
        <div class="list-header">
          <span class="list-header__title">共 {{ categories.length }} 个分类</span>
        </div>
      </template>
      <VLoading v-if="loading" />
      <Transition v-else-if="!categories.length" appear name="fade">
        <VEmpty title="暂无分类" message="创建分类（如 技术分享、问答、公告）来组织帖子">
          <template #actions>
            <VButton type="secondary" @click="openCreate">新建分类</VButton>
          </template>
        </VEmpty>
      </Transition>
      <Transition v-else appear name="fade">
        <Draggable
          v-model="categoriesTree"
          :class="{ 'is-dragging': positionUpdating }"
          :disable-drag="positionUpdating"
          trigger-class="drag-element"
          :indent="40"
          @after-drop="handleUpdatePosition"
        >
          <template #default="{ node }">
            <CategoryListItem
              :category-tree-node="node"
              @edit="openEdit(node)"
              @create-by-parent="openCreateByParent(node)"
              @saved="fetchCategories"
            />
          </template>
        </Draggable>
      </Transition>
    </VCard>
  </div>

  <CategoryEditingModal
    v-if="modalVisible"
    :category="editingCategory"
    :parent-category="creatingByParent"
    @saved="fetchCategories"
    @close="modalVisible = false"
  />
</template>

<style scoped>
/* bbs-header-icon / bbs-page-body 见 styles/tokens.css */
.list-header {
  display: flex;
  width: 100%;
  align-items: center;
  background: var(--bbs-bg-soft);
  padding: 0.75rem 1rem;
}
.list-header__title {
  font-size: 1rem;
  font-weight: 500;
  color: var(--bbs-text);
}

/* @he-tree 内部：分隔线 + 拖拽占位（对标官方） */
:deep(.vtlist-inner) {
  display: flex;
  flex-direction: column;
}
:deep(.vtlist-inner > *) {
  border-top: 1px solid var(--bbs-border);
}
:deep(.vtlist-inner > *:first-child) {
  border-top: 0;
}
:deep(.he-tree-drag-placeholder) {
  box-sizing: border-box;
  height: 48px;
  border: 1px dashed var(--bbs-accent);
  background: var(--bbs-bg-soft);
}
.is-dragging {
  opacity: 0.6;
  cursor: progress;
}
</style>
