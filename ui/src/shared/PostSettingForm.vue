<script setup lang="ts">
import { IconRefreshLine } from '@halo-dev/components'
import { slugify } from 'transliteration'
import type { PostFormState } from '@/types/bbs'

/**
 * 帖子设置表单（Console / UC 共用），分区布局对标官方文章设置弹窗：
 * 「常规」（类型/分类/别名）、「摘要」、「高级」（允许评论；置顶仅管理端）。
 *
 * 类型：管理端三选（讨论/问答/公告）；用户侧两选（讨论/问答），
 * 编辑公告时类型锁定展示（后端亦强制保持，防公告被降级）。
 * 通过 v-model 双向绑定整份 PostFormState（对象引用共享，父组件可直接读取）。
 */
const props = defineProps<{
  categories: { label: string; value: string }[]
  managed?: boolean
}>()

const form = defineModel<PostFormState>({ required: true })

// 别名按标题生成：用 transliteration 把中文等音译为 ASCII（对标 Halo 官方 use-slugify），
// 不保留中文字符，避免 /bbs/post/{中文} 的 URL 编码。
function generateSlug() {
  form.value.slug = slugify(form.value.title, { trim: true })
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
            { label: '讨论', value: 'POST' },
            { label: '问答', value: 'QUESTION' },
            { label: '公告', value: 'ANNOUNCEMENT' },
          ]"
        />
        <FormKit
          v-else-if="form.type === 'ANNOUNCEMENT'"
          v-model="form.type"
          type="radio"
          label="类型"
          disabled
          :options="[{ label: '公告', value: 'ANNOUNCEMENT' }]"
          help="公告类型仅管理端可调整"
        />
        <FormKit
          v-else
          v-model="form.type"
          type="radio"
          label="类型"
          :options="[
            { label: '讨论', value: 'POST' },
            { label: '问答', value: 'QUESTION' },
          ]"
          help="问答帖可在解决后标记「已解决」"
        />
        <FormKit
          v-model="form.categoryName"
          type="select"
          label="分类"
          :clearable="form.type === 'ANNOUNCEMENT'"
          :validation="form.type === 'ANNOUNCEMENT' ? '' : 'required'"
          placeholder="选择分类"
          :options="props.categories"
          :help="form.type === 'ANNOUNCEMENT' ? '可不选（不选时只在首页显示）' : '必选'"
        />
        <FormKit
          v-model="form.slug"
          type="text"
          label="别名 (slug)"
          help="前台链接 /bbs/post/{slug}；留空按标题生成"
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

    <div class="setting-grid">
      <div class="setting-grid__aside">
        <span class="setting-grid__section">高级</span>
      </div>
      <div class="setting-grid__main">
        <FormKit
          v-model="form.allowComment"
          type="switch"
          label="允许评论"
          help="关闭后前台不再显示评论输入（历史评论保留可见）"
        />
        <FormKit v-if="props.managed" v-model="form.pinned" type="switch" label="置顶"
          help="浮在所属分类页最前（未选分类则浮在首页最前），占用列表位置" />
        <FormKit
          v-if="props.managed && form.pinned"
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
