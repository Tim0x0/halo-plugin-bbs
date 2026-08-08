<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { loadIcon } from '@iconify/vue'
import { VModal, VButton, VSpace, Toast, IconRefreshLine } from '@halo-dev/components'
import { categoryApi } from '@/api/bbs'
import type { BbsCategorySpec, CategoryVo, Metadata } from '@/types/bbs'

/**
 * 分类创建/编辑对话框，对标 Halo 官方分类编辑：分区布局（常规/展示）、slug 自定义
 * 链接（带「按名称重新生成」+ 唯一性预检，后端唯一索引兜底）、Iconify 图标选择器。
 *
 * 图标控件对齐 settings.yaml 的公告/置顶图标：format=svg（不设 valueOnly）。
 * 原因：format=name 时 Halo 触发器只渲染 <Icon :icon>，不读 color，选色后控件仍无色；
 * format=svg 会把 color 烤进 SVG 文本，触发器 v-html 后即可见（与设置页一致）。
 *
 * 保存时拆成：
 * - spec.icon：图标名（name 字段）
 * - spec.color：选色 HEX
 * - spec.iconSvg：currentColor 离线 SVG（前台用 CSS color 着色，不绑死烤色）
 *
 * 事件契约：saved=已保存（父组件刷新列表，不关闭）；close=关闭对话框。
 */
const props = defineProps<{ category?: CategoryVo }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

/** Halo iconify 非 valueOnly 时的值形态 */
interface IconifyValue {
  value?: string
  name?: string
  width?: string
  color?: string
}

const isUpdate = computed(() => !!props.category)
const saving = ref(false)
const formState = ref<BbsCategorySpec>(defaultSpec())
// 图标选择器绑定对象，保存时再拆到 formState.icon / color
const iconifyValue = ref<IconifyValue | undefined>()
// FormKit form 引用：footer 按钮经 node.submit() 走标准提交（required 校验生效）
const formRef = ref()
const keepAddingFlag = ref(false)
// 全量分类（供父级选项与 slug 唯一性预检共用）
const allCategories = ref<{ name: string; displayName: string; parentName?: string }[]>([])

/** 父级选项：仅一级分类（两级封顶），排除自身 */
const parentOptions = computed(() => [
  { label: '无（一级分类）', value: '' },
  ...allCategories.value
    .filter((c) => !c.parentName && c.name !== props.category?.name)
    .map((c) => ({ label: c.displayName, value: c.name })),
])

/** 自己有子分类时不可再设父级（两级封顶） */
const hasChildren = computed(() =>
  allCategories.value.some((c) => c.parentName === props.category?.name)
)

function defaultSpec(): BbsCategorySpec {
  return {
    displayName: '',
    slug: '',
    description: '',
    icon: '',
    iconSvg: '',
    color: '',
    parentName: '',
    cover: '',
    priority: 0,
    enabled: true,
  }
}

function submitForm(keepAdding: boolean) {
  keepAddingFlag.value = keepAdding
  formRef.value?.node.submit()
}

/** 合法 HEX 才采纳；空或非法返回空串 */
function sanitizeHex(color?: string): string {
  const c = (color || '').trim()
  return /^#([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$/.test(c) ? c : ''
}

/**
 * 从 iconify 对象取出图标名。
 * format=svg 时 value 是 SVG 文本，只能信 name；name 缺失则视为无图标。
 */
function iconNameOf(raw?: IconifyValue): string {
  if (!raw) {
    return ''
  }
  return (raw.name || '').trim()
}

/** 把 currentColor SVG 临时换成实色，供 format=svg 控件回显（与选色后触发器观感一致） */
function colorizeSvg(svg: string, color?: string): string {
  const hex = sanitizeHex(color)
  if (!svg) {
    return ''
  }
  if (!hex) {
    return svg
  }
  return svg.replace(/currentColor/g, hex)
}

onMounted(async () => {
  if (props.category) {
    const icon = props.category.icon || ''
    const color = sanitizeHex(props.category.color)
    const iconSvg = props.category.iconSvg || ''
    formState.value = {
      displayName: props.category.displayName,
      slug: props.category.slug,
      description: props.category.description || '',
      icon,
      iconSvg,
      color,
      parentName: props.category.parentName || '',
      cover: props.category.cover || '',
      priority: props.category.priority ?? 0,
      enabled: props.category.enabled !== false,
    }
    // 回显到 iconify（format=svg）：value 必须是 SVG 文本；用 colorize 让触发器带色
    iconifyValue.value = icon
      ? {
          name: icon,
          value: colorizeSvg(iconSvg, color),
          color: color || undefined,
        }
      : undefined
  }
  // 载入分类全集：父级选项 + slug 预检共用
  try {
    const { data } = await categoryApi.list({ size: 200 })
    allCategories.value = (data.items || []).map((it) => ({
      name: it.metadata.name,
      displayName: it.spec.displayName,
      parentName: it.spec.parentName || '',
    }))
  } catch {
    /* 拉取失败仅影响父级选项，请求错误由全局拦截器提示 */
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

/** 把 Iconify 图标名解析为自包含 SVG 文本（currentColor，前台用 color 着色）；失败返回空串。 */
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
  // 两级封顶校验：有子分类的分类不可再设父级；父级必须是一级分类
  if (formState.value.parentName) {
    if (hasChildren.value) {
      Toast.warning('该分类下已有子分类，不能再设置父级')
      return
    }
    const parent = allCategories.value.find((c) => c.name === formState.value.parentName)
    if (parent?.parentName) {
      Toast.warning('父级必须是一级分类（仅支持两级）')
      return
    }
  }
  // 从 iconify 对象拆出图标名与颜色；无图标则颜色一并清空
  const iconName = iconNameOf(iconifyValue.value)
  const color = iconName ? sanitizeHex(iconifyValue.value?.color) : ''
  formState.value.icon = iconName
  formState.value.color = color

  saving.value = true
  try {
    // 始终按图标名解析 currentColor SVG，不直接存控件里的烤色 SVG
    formState.value.iconSvg = await resolveIconSvg(iconName)
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
      iconifyValue.value = undefined
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
            placeholder="如：技术分享、问答、公告"
          />
          <FormKit
            v-model="formState.slug"
            type="text"
            label="别名 (slug)"
            help="前台链接 /bbs?category={slug}；留空按名称生成"
          >
            <template #suffix>
              <div v-tooltip="'根据分类名称重新生成'" class="slug-refresh" @click="generateSlug">
                <IconRefreshLine class="slug-refresh__icon" />
              </div>
            </template>
          </FormKit>
          <FormKit v-model="formState.description" type="textarea" label="描述" :rows="2" />
          <FormKit
            v-model="formState.parentName"
            type="select"
            label="父级分类"
            :options="parentOptions"
            :disabled="hasChildren"
            :help="
              hasChildren
                ? '该分类下已有子分类，不能再设置父级（仅支持两级）'
                : '选择后成为其子分类；仅支持两级'
            "
          />
        </div>
      </div>

      <div class="setting-grid">
        <div class="setting-grid__aside">
          <span class="setting-grid__section">展示</span>
        </div>
        <div class="setting-grid__main">
          <!-- format=svg：与 settings 公告/置顶图标同款，选色会写入 SVG，触发器可见颜色 -->
          <FormKit
            v-model="iconifyValue"
            type="iconify"
            format="svg"
            label="分类图标"
            help="可选颜色；无图标则不设分类色"
          />
          <FormKit
            v-model="formState.cover"
            type="attachment"
            label="封面图"
            :help="
              formState.parentName
                ? '分类页顶部背景；留空继承父分类封面，父级也无则用分类色'
                : '分类页顶部背景；留空时前台以分类色渐变兜底'
            "
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
            help="停用后前台不展示"
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
