<script setup lang="ts">
import { IconRefreshLine } from '@halo-dev/components'
import type { PostFormState } from '@/types/bbs'

/**
 * 帖子设置表单（Console / UC 共用），分区布局对标官方文章设置弹窗：
 * 「常规」（类型/分类/别名）、「摘要」、「高级」（置顶，仅管理端）。
 *
 * managed=true 时展示管理特权字段（类型 / 置顶 / 置顶权重）。
 * 通过 v-model 双向绑定整份 PostFormState（对象引用共享，父组件可直接读取）。
 */
const props = defineProps<{
  categories: { label: string; value: string }[]
  managed?: boolean
}>()

const form = defineModel<PostFormState>({ required: true })

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
  form.value.slug = slugify(form.value.title)
}
</script>

<template>
  <div>
    <div class="setting-grid">
      <div class="setting-grid__aside">
        <span class="setting-grid__section">常规</span>
      </div>
      <div class="setting-grid__main">
        <FormKit
          v-if="props.managed"
          v-model="form.type"
          type="radio"
          label="类型"
          :options="[
            { label: '普通帖子', value: 'POST' },
            { label: '置顶公告（展示在前台顶部公告区）', value: 'ANNOUNCEMENT' },
          ]"
        />
        <FormKit
          v-model="form.categoryName"
          type="select"
          label="分类"
          clearable
          placeholder="选择分类"
          :options="props.categories"
          :help="
            form.type === 'ANNOUNCEMENT'
              ? '公告可不选分类（作为全站公告）'
              : '普通帖子建议选择分类'
          "
        />
        <FormKit
          v-model="form.slug"
          type="text"
          label="别名 (slug)"
          help="作为前台永久链接 /bbs/post/{slug}，留空自动按标题生成"
        >
          <template #suffix>
            <div v-tooltip="'根据标题重新生成'" class="slug-refresh" @click="generateSlug">
              <IconRefreshLine class="slug-refresh__icon" />
            </div>
          </template>
        </FormKit>
      </div>
    </div>

    <div class="setting-grid">
      <div class="setting-grid__aside">
        <span class="setting-grid__section">摘要</span>
      </div>
      <div class="setting-grid__main">
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

    <div v-if="props.managed" class="setting-grid">
      <div class="setting-grid__aside">
        <span class="setting-grid__section">高级</span>
      </div>
      <div class="setting-grid__main">
        <FormKit v-model="form.pinned" type="switch" label="置顶" />
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
  </div>
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
