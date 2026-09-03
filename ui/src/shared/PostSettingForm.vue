<script setup lang="ts">
import { computed, ref } from 'vue'
import { IconRefreshLine } from '@halo-dev/components'
import { FormType } from '@halo-dev/ui-shared'
import { consoleApi, ucApi } from '@/api/bbs'
import { useSlugify } from '@/composables/use-slugify'
import { typeOptions } from '@/utils/post-labels'
import BbsCategorySelect from '@/shared/BbsCategorySelect.vue'
import type { CategoryVo, PostFormState } from '@/types/bbs'

/**
 * 帖子设置表单（Console / UC 共用），分区布局对标官方文章设置弹窗：
 * 「常规」（标题/类型/分类/别名）、「摘要」、「高级」（置顶仅管理端）。
 *
 * 标题一直在表单里（官方设置弹窗同样如此）：编辑器顶部那个标题输入框与这里绑的是
 * 同一份 form.title，弹窗盖住编辑器、同一时刻只看得到一个，不会互相打架；
 * 而新建帖子点「发布」时弹出的就是这个表单，标题正好在这里补齐。
 *
 * 类型：管理端三选（讨论/问答/公告）；用户侧两选（讨论/问答），
 * 编辑公告时类型锁定展示（后端亦强制保持，防公告被降级）。
 * 通过 v-model 双向绑定整份 PostFormState（对象引用共享，父组件可直接读取）。
 */
const props = defineProps<{
  categories: CategoryVo[]
  managed?: boolean
  /** 当前编辑帖子的 metadata.name */
  postName?: string
}>()

const emit = defineEmits<{ (e: 'submit'): void }>()

const form = defineModel<PostFormState>({ required: true })

/**
 * FormKit form 实例。父组件的保存按钮经 submit() 触发标准提交流程——
 * 校验不通过就不会 emit('submit')，从而真正拦住提交（官方行为）。
 * 用 form ref 的 node 而非 @formkit/core 的 submitForm：后者不在插件的
 * 共享依赖里，import 会打包出第二份 FormKit registry。
 */
const formRef = ref()

function submit() {
  formRef.value?.node.submit()
}

defineExpose({ submit })

/** 有 postName 即编辑既有帖子；新建时才让别名随标题联想 */
const isUpdateMode = computed(() => !!props.postName)

/**
 * 别名生成，行为对齐官方：**只在新建时随标题联想**，编辑已有帖子改标题不动别名
 * （否则改个错别字就把已发布的链接改了）；生成策略跟随站点设置。
 * 「重新生成」按钮传 forceUpdate 强制覆盖。
 */
const { handleGenerateSlug } = useSlugify(
  computed(() => form.value.title),
  computed({
    get: () => form.value.slug,
    set: (value) => {
      form.value.slug = value
    },
  }),
  computed(() => !isUpdateMode.value),
  FormType.POST
)

/**
 * 别名唯一性：走 FormKit 校验规则，错误挂字段下方（对齐官方设置弹窗的
 * slugUniqueValidation 与本项目分类表单同款做法），不必等发布时撞 400。
 * 后端预检口径 = 发布占用口径（已发布 / 待审核 / 已提交修改稿），
 * 且服务端落库前会做同样归一，故这里直接提交用户输入。
 */
async function slugUniqueValidation(node: { value?: unknown }) {
  const slug = String(node.value || '').trim()
  if (!slug) {
    return true
  }
  try {
    const api = props.managed ? consoleApi : ucApi
    const { data } = await api.isSlugTaken(slug, props.postName)
    return !data
  } catch {
    // 预检失败不阻塞提交；唯一性最终防线在后端发布链路
    return true
  }
}
</script>

<template>
  <FormKit
    ref="formRef"
    type="form"
    :actions="false"
    :config="{ validationVisibility: 'submit' }"
    @submit="emit('submit')"
  >
    <div class="bbs-setting-grid">
      <div class="bbs-setting-grid__aside">
        <span class="bbs-setting-grid__section">常规</span>
      </div>
      <div class="bbs-setting-grid__main">
        <!-- 顺序对齐官方文章设置弹窗：标题与别名相邻（两者本就有联想关系），
             BBS 特有的类型 / 分类紧随其后。
             标题不写死 length 上限：上限是插件设置项（默认 100，可改），
             写死会与后端口径冲突；超长由后端返回可读错误 -->
        <FormKit
          v-model="form.title"
          type="text"
          label="标题"
          validation="required"
          placeholder="帖子标题"
        />
        <FormKit
          v-model="form.slug"
          type="text"
          label="别名 (slug)"
          validation="required|length:0,200|(500)slugUniqueValidation"
          :validation-rules="{ slugUniqueValidation }"
          :validation-messages="{ slugUniqueValidation: '该别名已被占用，请更换' }"
          help="前台链接 /bbs/post/{slug}"
        >
          <template #suffix>
            <div
              v-tooltip="'根据标题重新生成'"
              class="bbs-slug-refresh"
              @click="handleGenerateSlug(true)"
            >
              <IconRefreshLine class="bbs-slug-refresh__icon" />
            </div>
          </template>
        </FormKit>
        <FormKit
          v-if="props.managed"
          v-model="form.type"
          type="radio"
          label="类型"
          :options="typeOptions('POST', 'QUESTION', 'ANNOUNCEMENT')"
        />
        <FormKit
          v-else-if="form.type === 'ANNOUNCEMENT'"
          v-model="form.type"
          type="radio"
          label="类型"
          disabled
          :options="typeOptions('ANNOUNCEMENT')"
          help="公告类型仅管理端可调整"
        />
        <FormKit
          v-else
          v-model="form.type"
          type="radio"
          label="类型"
          :options="typeOptions('POST', 'QUESTION')"
          help="问答帖可在解决后标记「已解决」"
        />
        <!-- 分类：原生 select 画不了色点/图标，也没法把父级做成弱化前缀，
             故用自绘选择器塞进 FormKit 的 #input 段——label / help / 校验报错
             仍由 FormKit 渲染，与其它字段外观一致 -->
        <FormKit
          v-model="form.categoryName"
          type="text"
          label="分类"
          validation="required"
          :validation-messages="{ required: '请选择分类' }"
          help="必选（讨论 / 问答 / 公告均须归属分类）"
        >
          <template #input="ctx">
            <BbsCategorySelect
              :model-value="String(ctx.value ?? '')"
              :categories="props.categories"
              @update:model-value="ctx.node.input($event)"
            />
          </template>
        </FormKit>
      </div>
    </div>

    <div class="bbs-setting-grid">
      <div class="bbs-setting-grid__aside">
        <span class="bbs-setting-grid__section">摘要</span>
      </div>
      <div class="bbs-setting-grid__main">
        <FormKit v-model="form.autoExcerpt" type="switch" label="自动生成摘要" />
        <FormKit
          v-if="!form.autoExcerpt"
          v-model="form.excerpt"
          type="textarea"
          label="摘要"
          :rows="3"
          help="列表与前台展示用"
        />
      </div>
    </div>

    <!-- 高级区整块按管理端显示：限制手段只留锁定后，这里只剩置顶相关字段，
         UC 侧全是隐藏的——不整块藏起来会留下一个只有「高级」标题的空分区 -->
    <div v-if="props.managed" class="bbs-setting-grid">
      <div class="bbs-setting-grid__aside">
        <span class="bbs-setting-grid__section">高级</span>
      </div>
      <div class="bbs-setting-grid__main">
        <FormKit
          v-model="form.pinned"
          type="switch"
          label="置顶"
          help="浮在所属分类页顶部；若该分类开启「置顶帖上首页」则同时出现在首页顶部"
        />
        <FormKit
          v-if="form.pinned"
          v-model="form.pinPriority"
          type="number"
          number
          label="置顶权重"
          help="值越大越靠前"
        />
      </div>
    </div>
  </FormKit>
</template>

<!-- bbs-setting-grid / bbs-slug-refresh 系列见 styles/tokens.css -->
