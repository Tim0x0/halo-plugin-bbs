<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { loadIcon, Icon } from '@iconify/vue'
import { VModal, VButton, VSpace, Toast, IconRefreshLine } from '@halo-dev/components'
import { categoryApi } from '@/api/bbs'
import type { BbsCategorySpec, CategoryVo, Metadata } from '@/types/bbs'

/**
 * 分类创建/编辑对话框，对标 Halo 官方分类编辑：分区布局（常规/展示）、slug 自定义
 * 链接（带「按名称重新生成」+ 唯一性预检，后端唯一索引兜底）、Iconify 图标选择器。
 *
 * 保存时把 Iconify 图标名解析为内联 SVG 写入 spec.iconSvg（前台离线渲染用，
 * 运行时不再依赖 api.iconify.design）；解析失败置空并照常保存。
 *
 * 事件契约：saved=已保存（父组件刷新列表，不关闭）；close=关闭对话框。
 */
const props = defineProps<{ category?: CategoryVo }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

const isUpdate = computed(() => !!props.category)
const saving = ref(false)
const formState = ref<BbsCategorySpec>(defaultSpec())
// FormKit form 引用：footer 按钮经 node.submit() 走标准提交（required 校验生效）
const formRef = ref()
const keepAddingFlag = ref(false)

function defaultSpec(): BbsCategorySpec {
  return {
    displayName: '',
    slug: '',
    description: '',
    icon: '',
    iconSvg: '',
    color: '#6366f1',
    priority: 0,
    enabled: true,
  }
}

function submitForm(keepAdding: boolean) {
  keepAddingFlag.value = keepAdding
  formRef.value?.node.submit()
}

onMounted(() => {
  if (props.category) {
    formState.value = {
      displayName: props.category.displayName,
      slug: props.category.slug,
      description: props.category.description || '',
      icon: props.category.icon || '',
      iconSvg: props.category.iconSvg || '',
      color: props.category.color || '#6366f1',
      priority: props.category.priority ?? 0,
      enabled: props.category.enabled !== false,
    }
  }
})

function slugify(s?: string) {
  return (s || '')
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
    .replace(/[^a-z0-9一-龥-]+/g, '')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function generateSlug() {
  formState.value.slug = slugify(formState.value.displayName)
}

// 别名唯一性预检：查询同 slug 的分类，排除自身（后端唯一索引兜底并发场景）
async function slugAvailable(slug: string) {
  try {
    const { data } = await categoryApi.list({ size: 200 })
    return !(data.items || []).some(
      (it) => it.spec.slug === slug && it.metadata.name !== props.category?.name
    )
  } catch {
    return true
  }
}

/** 把 Iconify 图标名解析为自包含 SVG 文本（前台离线渲染）；失败返回空串。 */
async function resolveIconSvg(iconName?: string): Promise<string> {
  const name = (iconName || '').trim()
  if (!name) {
    return ''
  }
  try {
    const icon = await loadIcon(name)
    if (!icon) {
      return ''
    }
    const left = icon.left ?? 0
    const top = icon.top ?? 0
    const width = icon.width ?? 16
    const height = icon.height ?? 16
    return (
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${left} ${top} ${width} ${height}"` +
      ` width="1em" height="1em" fill="currentColor" aria-hidden="true">${icon.body}</svg>`
    )
  } catch {
    return ''
  }
}

async function onSubmit() {
  const keepAdding = keepAddingFlag.value
  if (!formState.value.displayName.trim()) {
    Toast.warning('请填写分类名称')
    return
  }
  if (!formState.value.slug?.trim()) {
    formState.value.slug = slugify(formState.value.displayName)
  }
  if (!formState.value.slug) {
    Toast.warning('请填写别名 (slug)')
    return
  }
  if (!(await slugAvailable(formState.value.slug))) {
    Toast.warning('该别名(slug)已被占用，请更换')
    return
  }
  saving.value = true
  try {
    formState.value.iconSvg = await resolveIconSvg(formState.value.icon)
    if (isUpdate.value) {
      await categoryApi.patch(props.category!.name, [
        { op: 'replace', path: '/spec', value: formState.value },
      ])
      Toast.success('已更新')
    } else {
      await categoryApi.create({
        apiVersion: 'bbs.timxs.com/v1alpha1',
        kind: 'BbsCategory',
        metadata: { generateName: 'bbs-category-' } as unknown as Metadata,
        spec: formState.value,
      })
      Toast.success('已创建')
    }
    emit('saved')
    if (keepAdding && !isUpdate.value) {
      formState.value = defaultSpec()
    } else {
      emit('close')
    }
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <VModal
    :title="isUpdate ? '编辑分类' : '新建分类'"
    :width="680"
    mount-to-body
    @close="emit('close')"
  >
    <FormKit ref="formRef" type="form" :actions="false" @submit="onSubmit">
      <div class="setting-grid">
        <div class="setting-grid__aside">
          <span class="setting-grid__section">常规</span>
        </div>
        <div class="setting-grid__main">
          <FormKit
            v-model="formState.displayName"
            type="text"
            label="分类名称"
            validation="required"
            placeholder="如：CS1.6、DNF、插件专区"
          />
          <FormKit
            v-model="formState.slug"
            type="text"
            label="别名 (slug)"
            help="作为前台分类链接 /bbs?category={slug}，留空将按名称自动生成"
          >
            <template #suffix>
              <div v-tooltip="'根据分类名称重新生成'" class="slug-refresh" @click="generateSlug">
                <IconRefreshLine class="slug-refresh__icon" />
              </div>
            </template>
          </FormKit>
          <FormKit v-model="formState.description" type="textarea" label="描述" :rows="2" />
        </div>
      </div>

      <div class="setting-grid">
        <div class="setting-grid__aside">
          <span class="setting-grid__section">展示</span>
        </div>
        <div class="setting-grid__main">
          <div class="category-preview">
            <span
              class="category-tile"
              :style="{
                background: (formState.color || '#6366f1') + '1a',
                color: formState.color || '#6366f1',
              }"
            >
              <span v-if="formState.icon" class="category-tile__icon">
                <Icon :icon="formState.icon" />
              </span>
              <span
                v-else
                class="category-tile__dot"
                :style="{ background: formState.color || '#6366f1' }"
              ></span>
            </span>
            <span class="category-preview__name">
              {{ formState.displayName || '分类预览' }}
            </span>
          </div>
          <FormKit
            v-model="formState.icon"
            type="iconify"
            format="name"
            :value-only="true"
            label="分类图标"
            help="点击选择 Iconify 图标（保存时解析为离线 SVG），留空则用主题色色点"
          />
          <FormKit
            v-model="formState.color"
            type="color"
            label="主题色（图标着色 / 分类标识色）"
          />
          <FormKit
            v-model="formState.priority"
            type="number"
            number
            label="排序优先级"
            help="值越小越靠前"
          />
          <FormKit
            v-model="formState.enabled"
            type="switch"
            label="启用"
            help="停用后前台不再展示该分类"
          />
        </div>
      </div>
    </FormKit>

    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" @click="submitForm(false)">保存</VButton>
        <VButton v-if="!isUpdate" :loading="saving" @click="submitForm(true)">
          保存并继续创建
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
/* 分区布局：aside 分区标题 + 右侧字段（与官方文章设置弹窗同款） */
.setting-grid {
  display: block;
}

.setting-grid + .setting-grid {
  margin-top: 1.5rem;
  border-top: 1px solid var(--bbs-border);
  padding-top: 1.5rem;
}

@media (min-width: 768px) {
  .setting-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 1.5rem;
  }
}

.setting-grid__aside {
  position: sticky;
  top: 0;
}

.setting-grid__section {
  font-size: 1rem;
  font-weight: 500;
  color: var(--bbs-text);
}

.setting-grid__main {
  margin-top: 1.25rem;
}

@media (min-width: 768px) {
  .setting-grid__main {
    grid-column: span 3 / span 3;
    margin-top: 0;
  }
}

.setting-grid__main > :deep(*) + :deep(*) {
  border-top: 1px solid var(--bbs-border);
}

.category-preview {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.75rem;
  border: 1px dashed var(--bbs-border);
  border-radius: var(--bbs-radius-lg);
  background: var(--bbs-bg-soft);
}

.category-preview__name {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--bbs-text);
}

.category-tile {
  display: inline-grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 8px;
  font-size: 1rem;
}

.category-tile__icon {
  display: inline-flex;
  font-size: 1rem;
}

.category-tile__dot {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 4px;
}

.slug-refresh {
  display: flex;
  height: 100%;
  align-items: center;
  padding: 0 0.75rem;
  border-left: 1px solid var(--bbs-border);
  cursor: pointer;
  transition: var(--bbs-transition);
}

.slug-refresh:hover {
  background: var(--bbs-bg-hover);
}

.slug-refresh__icon {
  width: 1rem;
  height: 1rem;
  color: var(--bbs-text-muted);
}

.slug-refresh:hover .slug-refresh__icon {
  color: var(--bbs-text);
}
</style>
