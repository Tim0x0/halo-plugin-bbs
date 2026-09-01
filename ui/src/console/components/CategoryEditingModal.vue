<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue'
import { slugify } from 'transliteration'
import { FormType } from '@halo-dev/ui-shared'
import { VModal, VButton, VSpace, Toast, IconRefreshLine } from '@halo-dev/components'
import { categoryApi } from '@/api/bbs'
import { useSlugify } from '@/composables/use-slugify'
import { useCategoryColor } from '@/composables/use-category-color'
import { sanitizeHex } from '@/utils/color'
import type { BbsCategorySpec, CategoryVo, Metadata } from '@/types/bbs'

/**
 * 分类创建/编辑对话框，对标 Halo 官方分类编辑：分区布局（常规 / 展示 / 高级）、
 * slug 自定义链接、分类色（新建时随名称实时联想 + 「按名称重新生成」；
 * 编辑改名不换色）、唯一性预检、Iconify 图标选择器。
 *
 * 图标控件对齐 settings.yaml 的公告/置顶图标：format=svg（不设 valueOnly）。
 * 原因：format=name 时 Halo 触发器只渲染 <Icon :icon>，不读 color，选色后控件仍无色；
 * format=svg 会把 color 烤进 SVG 文本，触发器 v-html 后即可见（与设置页一致）。
 *
 * 保存时拆成：
 * - spec.icon：图标名（name 字段）
 * - spec.iconSvg：选择器输出的 SVG 原文（value 字段）——颜色已在其 fill 里，
 *   选色则是实色、未选色则是 currentColor 随文字色。不另存图标色：官方 color
 *   字段只是选择器自己的 UI 备忘录，渲染链一处都不读它
 * - spec.color：分类色（新建按名称预填），独立于图标，管色点 / Tag / 分类 Hero
 *
 * 事件契约：saved=已保存（父组件刷新列表，不关闭）；close=关闭对话框。
 */
const props = defineProps<{ category?: CategoryVo; parentCategory?: CategoryVo }>()
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
// VModal 实例：关闭走它的 close() 而非直接 emit，保留退场动画（对齐官方）
const modal = ref<InstanceType<typeof VModal>>()
// 图标选择器绑定对象，保存时再拆到 formState.icon / color
const iconifyValue = ref<IconifyValue | undefined>()
// FormKit form 引用：footer 按钮经 node.submit() 走标准提交（required 校验生效）
const formRef = ref()
const keepAddingFlag = ref(false)
// 全量分类（供父级选项、slug 预检、新建 priority 计算共用）
const allCategories = ref<
  { name: string; displayName: string; parentName?: string; priority?: number }[]
>([])

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

async function loadSpec(name: string): Promise<BbsCategorySpec> {
  const { data } = await categoryApi.get(name)
  return data.spec
}

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
    pinToHome: false,
    moderatorRoles: [],
  }
}

function submitForm(keepAdding: boolean) {
  keepAddingFlag.value = keepAdding
  formRef.value?.node.submit()
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

/**
 * 聚焦「分类名称」输入框（对齐官方 setFocus('displayNameInput')）。
 * 官方那个 setFocus 是 Halo 应用内工具、插件取不到，这里用原生 DOM——
 * 拿不到元素时静默跳过，不影响功能。
 */
function focusDisplayName() {
  nextTick(() => {
    const input = document.getElementById('categoryDisplayNameInput')
    if (input instanceof HTMLInputElement) {
      input.focus()
    }
  })
}

onMounted(async () => {
  if (props.category) {
    // 回填必须用 spec 原值，不能用 VO 的封面/色回退——否则只改名字保存会把父封面、
    // 或展示用的哈希色写进 spec
    let spec: BbsCategorySpec
    try {
      spec = await loadSpec(props.category.name)
    } catch {
      // VO 不含版主配置 / 原封面 / 原色；残缺数据上保存会整段 replace /spec
      // 把 moderatorRoles 清掉。请求错误由全局拦截器提示，这里只关窗。
      if (modal.value) {
        modal.value.close()
      } else {
        emit('close')
      }
      return
    }
    const icon = spec.icon || ''
    const color = sanitizeHex(spec.color)
    const iconSvg = spec.iconSvg || ''
    formState.value = {
      displayName: spec.displayName,
      slug: spec.slug,
      description: spec.description || '',
      icon,
      iconSvg,
      color,
      parentName: spec.parentName || '',
      cover: spec.cover || '',
      priority: spec.priority ?? 0,
      enabled: spec.enabled !== false,
      pinToHome: !!spec.pinToHome,
      moderatorRoles: spec.moderatorRoles || [],
    }
    // 回显到 iconify（format=svg）：value 就是落库的 SVG（颜色已在其 fill 里），
    // 触发器 v-html 后观感与保存时一致。color 不回填——官方那个字段只是选择器自己的
    // UI 备忘录，我们不落库，重开选色器时用户看到的是未选态
    iconifyValue.value = icon ? { name: icon, value: iconSvg } : undefined
  } else if (props.parentCategory) {
    // 创建子分类：预设父级（对标官方 handleOpenCreateByParentModal）
    formState.value.parentName = props.parentCategory.name
  }
  // 载入分类全集：父级选项 + 新建 priority（slug 判重已改走 fieldSelector 精确查询）。
  // size 与官方 getNextPriority 对齐取 1000——截断会让 max+1 算出重复 priority、
  // 也会让父级下拉缺项
  try {
    const { data } = await categoryApi.list({ size: 1000 })
    allCategories.value = (data.items || []).map((it) => ({
      name: it.metadata.name,
      displayName: it.spec.displayName,
      parentName: it.spec.parentName || '',
      priority: it.spec.priority ?? 0,
    }))
  } catch {
    /* 拉取失败仅影响父级选项，请求错误由全局拦截器提示 */
  }
  focusDisplayName()
})

/**
 * 新建时的 priority：同级现有数量（0-based 追加到末尾），与官方 getNextPriority 一致。
 *
 * 该口径的前提是同级 priority 始终连续——由后端位置 API 的重排保证。
 * 编辑时保留原值。
 */
function nextPriority(parentName: string): number {
  return allCategories.value.filter((c) => (c.parentName || '') === (parentName || '')).length
}

/**
 * 别名生成，与官方文章分类一致：**新建时随分类名实时联想**（边打名字边出别名），
 * 编辑已有分类时不再自动改——否则改个错别字就把已发布的分类链接换掉了。
 * 右侧刷新按钮传 forceUpdate 强制按当前名称重算（编辑态下的唯一改法）。
 */
const { handleGenerateSlug } = useSlugify(
  computed(() => formState.value.displayName),
  computed({
    get: () => formState.value.slug || '',
    set: (value) => {
      formState.value.slug = value
    },
  }),
  computed(() => !isUpdate.value),
  FormType.CATEGORY
)

/** 分类色：新建随名称实时哈希；编辑改名不动，点刷新才重算（和 slug 同一套习惯） */
const { handleGenerateColor } = useCategoryColor(
  computed(() => formState.value.displayName),
  computed({
    get: () => formState.value.color || '',
    set: (value) => {
      formState.value.color = value
    },
  }),
  computed(() => !isUpdate.value)
)

/**
 * 别名唯一性：走 FormKit 校验规则，错误挂在字段下方（对齐官方 slugUniqueValidation），
 * 不再在提交函数里 await + Toast。后端唯一索引兜底并发场景。
 */
async function slugUniqueValidation(node: { value?: unknown }) {
  const slug = slugify(String(node.value || '').trim(), { trim: true })
  if (!slug) {
    return true
  }
  try {
    return !(await categoryApi.isSlugTaken(slug, props.category?.name))
  } catch {
    // 预检失败不阻塞提交，交给后端唯一索引兜底
    return true
  }
}

/**
 * 两级封顶：目标父级必须是一级分类。同样做成校验规则挂在父级字段下方，
 * 而不是提交时弹 Toast——用户选完就能看到问题，不用等到点保存。
 */
function parentLevelValidation(node: { value?: unknown }) {
  // 自身已有子分类时该字段是 disabled 的，用户根本改不了。若存量脏数据里它还带着
  // 父级（两级封顶之前的遗留），不该因此把整个表单卡死、连改个名字都保存不了
  if (hasChildren.value) {
    return true
  }
  const parentName = String(node.value || '').trim()
  if (!parentName) {
    return true
  }
  const parent = allCategories.value.find((c) => c.name === parentName)
  return !parent?.parentName
}

async function onSubmit() {
  const keepAdding = keepAddingFlag.value
  // 名称必填、别名唯一性、两级封顶均由 FormKit 校验规则负责（错误挂在字段下方），
  // 此处不再重复弹 Toast
  if (!formState.value.slug?.trim()) {
    formState.value.slug = slugify(formState.value.displayName)
  }
  // 图标名 + SVG 都从 iconify 取：format=svg 的 value 已是最终 SVG，
  // 选色烤进 fill、未选色留 currentColor（随所在位置文字色）。
  // 不自己 loadIcon 重拼——那会丢掉选色，也就不需要单独存图标色了
  const iconName = iconNameOf(iconifyValue.value)
  formState.value.icon = iconName
  if (iconName) {
    // 只在拿到 SVG 时才覆盖：控件是异步 fetch Iconify 的，选完图标立刻保存时
    // value 可能还没回来——那就保留原 SVG，别把已有图标清成空
    const svg = iconifyValue.value?.value
    if (svg) {
      formState.value.iconSvg = svg
    }
  } else {
    formState.value.iconSvg = ''
  }
  formState.value.color = sanitizeHex(formState.value.color)
  // 选了父级就不是板块了：开关在表单里已隐藏，值也要一并清掉，免得留脏数据
  // （后端调和器还有一道兜底，覆盖绕过表单的 CRUD PATCH）
  if (formState.value.parentName) {
    formState.value.pinToHome = false
    formState.value.moderatorRoles = []
  }
  // priority：编辑保留载入值；新建自动算（表单不暴露，对齐官方）
  if (!isUpdate.value) {
    formState.value.priority = nextPriority(formState.value.parentName || '')
  }

  saving.value = true
  try {
    if (isUpdate.value) {
      await categoryApi.patch(props.category!.name, [
        { op: 'replace', path: '/spec', value: formState.value },
      ])
      Toast.success('已更新')
    } else {
      const { data: created } = await categoryApi.create({
        apiVersion: 'bbs.timxs.com/v1alpha1',
        kind: 'BbsCategory',
        metadata: { generateName: 'bbs-category-' } as unknown as Metadata,
        spec: formState.value,
      })
      // 「保存并继续」时并入本地列表，保证 nextPriority 递增（即使响应缺 name 也记 priority）
      allCategories.value.push({
        name: created?.metadata?.name || `__local-${Date.now()}`,
        displayName: created?.spec?.displayName || formState.value.displayName,
        parentName: (created?.spec?.parentName ?? formState.value.parentName) || '',
        priority: created?.spec?.priority ?? formState.value.priority ?? 0,
      })
      Toast.success('已创建')
    }
    emit('saved')
    if (keepAdding && !isUpdate.value) {
      const keepParent = formState.value.parentName || ''
      formState.value = defaultSpec()
      formState.value.parentName = keepParent
      iconifyValue.value = undefined
      focusDisplayName()
    } else {
      modal.value?.close()
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
    ref="modal"
    :title="isUpdate ? '编辑分类' : '新建分类'"
    :width="700"
    mount-to-body
    @close="emit('close')"
  >
    <FormKit ref="formRef" type="form" :actions="false" @submit="onSubmit">
      <!-- 常规区顺序对齐 Halo 官方文章分类弹窗：父 → 名 → slug → 描述 -->
      <div class="bbs-setting-grid">
        <div class="bbs-setting-grid__aside">
          <span class="bbs-setting-grid__section">常规</span>
        </div>
        <div class="bbs-setting-grid__main">
          <FormKit
            v-model="formState.parentName"
            type="select"
            label="父级分类"
            :options="parentOptions"
            :disabled="hasChildren"
            validation="parentLevelValidation"
            :validation-rules="{ parentLevelValidation }"
            :validation-messages="{
              parentLevelValidation: '父级必须是一级分类（仅支持两级）',
            }"
            :help="
              hasChildren
                ? '该分类下已有子分类，不能再设置父级（仅支持两级）'
                : '留空则为一级分类'
            "
          />
          <FormKit
            id="categoryDisplayNameInput"
            v-model="formState.displayName"
            type="text"
            label="分类名称"
            validation="required|length:0,100"
            placeholder="如：技术分享、问答、公告"
          />
          <FormKit
            v-model="formState.slug"
            type="text"
            label="别名 (slug)"
            validation="required|length:0,100|(500)slugUniqueValidation"
            :validation-rules="{ slugUniqueValidation }"
            :validation-messages="{ slugUniqueValidation: '该别名已被占用，请更换' }"
            help="前台链接 /bbs?category={slug}；留空按名称生成"
          >
            <template #suffix>
              <div
                v-tooltip="'根据分类名称重新生成'"
                class="bbs-slug-refresh"
                @click="handleGenerateSlug(true)"
              >
                <IconRefreshLine class="bbs-slug-refresh__icon" />
              </div>
            </template>
          </FormKit>
          <FormKit
            v-model="formState.description"
            type="textarea"
            label="描述"
            :rows="2"
            auto-height
          />
        </div>
      </div>

      <!-- 展示区：只放「长什么样」——图标 / 分类色 / 封面图，从小到大按视觉层级排。
           priority 不进表单（拖拽排序 / 新建自动算） -->
      <div class="bbs-setting-grid">
        <div class="bbs-setting-grid__aside">
          <span class="bbs-setting-grid__section">展示</span>
        </div>
        <div class="bbs-setting-grid__main">
          <FormKit
            v-model="iconifyValue"
            type="iconify"
            format="svg"
            label="分类图标"
            help="可选。颜色在选择器里选，前台按原样显示；不选色则跟随所在位置的文字色"
          />
          <!-- Halo color 是色板按钮，没有文本框 suffix。标签自己画，刷新贴标签旁，
               不走 FormKit #label 槽——槽参数随版本变，赌输就没标签也没按钮 -->
          <div class="color-field">
            <div class="color-label">
              <span class="color-label__text">分类色</span>
              <button
                type="button"
                class="color-refresh"
                v-tooltip="'根据分类名称重新生成'"
                aria-label="根据分类名称重新生成"
                @click="handleGenerateColor(true)"
              >
                <IconRefreshLine class="color-refresh__icon" />
              </button>
            </div>
            <FormKit
              v-model="formState.color"
              type="color"
              format="hex8"
              help="新建时随名称生成实色；清空后前台不上色。编辑改名不会换色，点标签旁刷新按名称重算"
            />
          </div>
          <FormKit
            v-model="formState.cover"
            type="attachment"
            label="封面图"
            :accepts="['image/*']"
            :help="
              formState.parentName
                ? '分类页顶部背景；留空继承父分类封面，父级也无则用分类色（分类色也空则中性底）'
                : '分类页顶部背景；留空时前台以分类色铺底，分类色也空则中性底'
            "
          />
        </div>
      </div>

      <!-- 高级区：影响「出现在哪、出不出现」的行为开关，与外观分开放，
           免得在挑图标选颜色的时候误碰到会改变前台可见性的开关 -->
      <div class="bbs-setting-grid">
        <div class="bbs-setting-grid__aside">
          <span class="bbs-setting-grid__section">高级</span>
        </div>
        <div class="bbs-setting-grid__main">
          <FormKit
            v-model="formState.enabled"
            type="switch"
            label="启用"
            help="停用后前台不展示"
          />
          <!-- 「上首页」是板块级特权：仅一级分类可开，开了即覆盖其全部子分类。
               选了父级即隐藏——开到叶子层会让每个子分类都能往首页塞置顶帖 -->
          <FormKit
            v-if="!formState.parentName"
            v-model="formState.pinToHome"
            type="switch"
            label="置顶帖上首页"
            help="本分类及其子分类下被置顶的帖会出现在首页列表顶部"
          />
          <!-- 版主授权同为板块级：仅一级分类可配，覆盖其全部子分类。
               用 Halo 官方 roleSelect（拉非模板的自建角色，value=metadata.name），
               多选——单选会导致「配了二级、三级反而进不来」 -->
          <FormKit
            v-if="!formState.parentName"
            v-model="formState.moderatorRoles"
            type="roleSelect"
            label="本板块版主角色"
            :multiple="true"
            searchable
            clearable
            help="持有所选角色的用户可管理本分类及其子分类下的帖子；留空则仅全站版主与管理员可管"
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
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
/* bbs-setting-grid / bbs-slug-refresh 系列见 styles/tokens.css */
.color-field :deep(.formkit-wrapper) {
  padding-top: 0.25rem;
}

.color-label {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.color-label__text {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--bbs-text);
}

.color-refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--bbs-text-muted);
}

.color-refresh:hover {
  color: var(--bbs-text);
}

.color-refresh__icon {
  width: 1rem;
  height: 1rem;
}
</style>
