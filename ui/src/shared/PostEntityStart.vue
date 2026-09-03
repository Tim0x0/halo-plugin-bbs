<script setup lang="ts">
/**
 * Console/UC 帖子行 #start
 *
 * 标记体系分五层，各有各的落点，避免同一行里混着多种视觉语言：
 * 1. **内容类型**（公告 / 问答）——彩色 VTag 紧跟标题；讨论是默认类型不加标记。
 * 2. **内容属性**（置顶 / 已解决）——标题后单色图标点阵 + tooltip，只读。
 * 3. **限制开关**（锁定 / 关闭回复）——#end 可点图标列，见 PostToggleFields。
 * 4. **发布状态**（已发布 / 未发布 / 待审核 / 已驳回）——#end 独立状态列纯文字，
 *    见 PostStatusEnd；每行有且只有一个，故单独占一列且宽度固定。
 * 5. **未发布内容**——标题侧状态点，官方 inProgress 的位置。官方口径
 *    （PostReconciler）：head ≠ release 即 inProgress，不分已发布 / 未发布——
 *    未发布帖的正文整体是未发布内容，同样打绿点。绿点闪烁=有未发布内容；
 *    已发布帖的修改待审=黄点闪烁、修改驳回=红点（审核信号优先于绿点）。
 *    状态列因此永远说主状态，两套语言不互相污染。
 *
 * 分类用「父级弱化前缀」呈现（`父分类 /` 更淡 + 分类名正常色），既保住层级
 * 信息又不让它变成一坨等权重长串，见 CategoryMark。
 */
import { computed, type Component } from 'vue'
import { formatTime } from '@/utils/date'
import { VEntityField, VStatusDot, VTag, IconExternalLinkLine } from '@halo-dev/components'
import RiMegaphoneLine from '~icons/ri/megaphone-line'
import RiQuestionLine from '~icons/ri/question-line'
import RiPushpin2Fill from '~icons/ri/pushpin-2-fill'
import RiCheckboxCircleFill from '~icons/ri/checkbox-circle-fill'
import type { BbsPostVo } from '@/types/bbs'
import CategoryMark from '@/shared/CategoryMark.vue'

const props = defineProps<{
  post: BbsPostVo
  titleRoute?: Record<string, unknown>
}>()

/** 内容类型徽标：公告 / 问答用 VTag（每行最多 1 个）；讨论无标记 */
const typeTag = computed(() => {
  if (props.post.type === 'ANNOUNCEMENT') {
    return {
      label: '公告',
      icon: RiMegaphoneLine,
      // 覆盖 tag-default 的灰描边：底色已是语义色的淡色调，再套灰边就脏了
      styles: {
        background: 'var(--bbs-warning-bg)',
        color: 'var(--bbs-warning)',
        border: '1px solid transparent',
      },
    }
  }
  if (props.post.type === 'QUESTION') {
    return {
      label: '问答',
      icon: RiQuestionLine,
      styles: {
        background: 'var(--bbs-info-bg)',
        color: 'var(--bbs-info)',
        border: '1px solid transparent',
      },
    }
  }
  return null
})

interface PostMark {
  key: string
  icon: Component
  tip: string
  tone: 'accent' | 'success'
}

/**
 * 标题后的状态标记组：只放**内容属性**（置顶 / 已解决）。
 *
 * 锁定与关闭回复是「限制类开关」，在 #end 的可点图标列（对齐 Halo 的
 * 「禁止访问」眼睛列，点一下就切）——见 PostToggleFields。这样标题行最多 2 个图标。
 */
const marks = computed(() => {
  const post = props.post
  const list: PostMark[] = []
  if (post.pinned) {
    list.push({ key: 'pinned', icon: RiPushpin2Fill, tip: '已置顶', tone: 'accent' })
  }
  if (post.type === 'QUESTION' && post.solved) {
    list.push({ key: 'solved', icon: RiCheckboxCircleFill, tip: '已解决', tone: 'success' })
  }
  return list
})

/**
 * 已编辑：直接用服务端派生的 edited（发布后正文有改动），与前台 /bbs 同一口径。
 * 语义对齐 Discourse/Flarum：首次发布前的打磨不算，未发布的工作稿改动不算
 * （后者由状态点表达）；只改设置也不算（设置变更不刷新正文编辑时间）。
 */
const isEdited = computed(() => Boolean(props.post.edited))
</script>

<template>
  <VEntityField :title="post.title" max-width="30rem" :route="titleRoute as any">
    <template #extra>
      <!-- 未发布内容状态（官方 inProgress 的位置，口径：head ≠ release 不分发布态）。
           extra 容器无 gap，间距靠元素自身 margin（同官方 .entity-field-title 的
           0.5rem 节奏），三个点各包一层 .entity-status-dot 给右侧留白 -->
      <span
        v-if="post.phase === 'PUBLISHED' && post.draftPhase === 'PENDING'"
        class="entity-status-dot"
      >
        <VStatusDot v-tooltip="'修改稿待审核；前台仍是已发布版本'" state="warning" animate />
      </span>
      <span
        v-else-if="post.phase === 'PUBLISHED' && post.draftPhase === 'REJECTED'"
        class="entity-status-dot"
      >
        <VStatusDot
          v-tooltip="post.rejectReason ? `修改稿驳回原因：${post.rejectReason}` : '修改稿已被驳回'"
          state="error"
          animate
        />
      </span>
      <!-- 未发布态（未发布/待审核/已驳回）正文整体未发布，等价官方 head ≠ release，
           同样打绿点；hasDraft 只对已发布帖计算，不能单独作为绿点条件 -->
      <span
        v-else-if="post.phase !== 'PUBLISHED' || post.hasDraft"
        class="entity-status-dot"
      >
        <VStatusDot
          v-tooltip="
            post.phase === 'PUBLISHED'
              ? '存在未提交的修改；前台仍是已发布版本'
              : '存在未发布的内容'
          "
          state="success"
          animate
        />
      </span>
      <span v-if="typeTag" class="entity-type">
        <VTag :styles="typeTag.styles">
          <template #leftIcon><component :is="typeTag.icon" class="entity-type__icon" /></template>
          {{ typeTag.label }}
        </VTag>
      </span>

      <span v-if="marks.length" class="entity-marks">
        <span
          v-for="mark in marks"
          :key="mark.key"
          v-tooltip="mark.tip"
          class="entity-mark"
          :class="`entity-mark--${mark.tone}`"
        >
          <component :is="mark.icon" class="entity-mark__icon" />
        </span>
      </span>

      <!-- 外链图标对齐官方：已发布→前台链接；未发布→预览路由（仅作者可看，
           其他人点开是 404——官方列表同款行为） -->
      <a
        v-if="post.phase !== 'PUBLISHED' || post.permalink"
        target="_blank"
        :href="post.phase === 'PUBLISHED' ? post.permalink : `/bbs/preview/${post.name}`"
        class="entity-permalink"
        @click.stop
      >
        <IconExternalLinkLine class="entity-permalink__icon" />
      </a>
      <slot name="extra" />
    </template>
    <template #description>
      <div class="entity-meta">
        <CategoryMark
          v-if="post.category"
          :display-name="post.category.displayName"
          :parent-name="post.category.parent?.displayName"
          :color="post.category.color"
          :icon-svg="post.category.iconSvg"
        />
        <CategoryMark v-else empty />

        <span
          v-if="isEdited"
          v-tooltip="formatTime(post.lastEditTime)"
          class="entity-meta__item entity-meta__faint"
        >
          已编辑
        </span>

        <slot name="description-extra" />
      </div>
    </template>
  </VEntityField>
</template>

<style scoped>
/* extra 容器（.entity-field-title-body）无 gap，间距全靠元素自身右边距，
   节奏对齐官方 .entity-field-title 的 margin-right: .5rem：
   状态点 0.5rem，类型徽标 / 属性图标组 0.25rem */
.entity-status-dot {
  display: inline-flex;
  align-items: center;
  flex: none;
  margin-right: 0.5rem;
}

.entity-type {
  display: inline-flex;
  align-items: center;
  margin-right: 0.25rem;
}

.entity-type__icon {
  width: 0.75rem;
  height: 0.75rem;
}

/* 状态标记：单色图标 + 极淡底色，尺寸与 VTag 同高，hover 出文字 */
.entity-marks {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  margin-right: 0.25rem;
}

.entity-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.25rem;
  height: 1.25rem;
  border-radius: var(--bbs-radius);
  cursor: default;
}

.entity-mark__icon {
  width: 0.875rem;
  height: 0.875rem;
}

.entity-mark--accent {
  color: var(--bbs-accent);
  background: var(--bbs-accent-bg);
}

.entity-mark--success {
  color: var(--bbs-success);
  background: var(--bbs-success-bg);
}

/* 排版参考官方 description：小字灰阶 + wrap；间距略放宽（现代化） */
.entity-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
  font-size: 0.75rem;
  line-height: 1.25rem;
  color: var(--bbs-text-muted, #6b7280);
}

.entity-meta__item {
  flex: none;
}

.entity-meta__faint {
  color: var(--bbs-text-faint, #9ca3af);
}
</style>

<!--
  外链图标的显隐由**整行** hover 驱动（对齐官方文章列表），而触发者 `.entity-wrapper`
  是 VEntity 的根元素、本组件的祖先——scoped CSS 只能往下选，选不到祖先：
  `:deep(.x:hover) .y` 会编译成 `[data-v] .x:hover .y`，要求 .entity-wrapper 是本组件
  后代，永远匹配不上；`:global(.x:hover) .y` 则会被编译器丢掉后半段。
  故这组规则单独放在非 scoped 块里，类名带 entity- 前缀，冲突面可控。
-->
<style>
.entity-permalink {
  margin-left: 0.25rem;
  display: inline-flex;
  color: var(--bbs-text-faint, #9ca3af);
  opacity: 0;
  /* 隐藏时不吃点击：标题本身是跳编辑器的链接，点偏一点不该被弹去新标签页。
     用 pointer-events 而非 display/visibility——键盘 Tab 仍能聚焦到它 */
  pointer-events: none;
  transition: color 0.15s ease, opacity 0.15s ease;
}

.entity-wrapper:hover .entity-permalink,
.entity-permalink:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.entity-permalink:hover {
  color: var(--bbs-text, #111827);
}

.entity-permalink__icon {
  height: 0.875rem;
  width: 0.875rem;
}
</style>
