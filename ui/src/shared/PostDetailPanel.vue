<script setup lang="ts">
import type { PostDetailItem } from '@/types/bbs'

/**
 * 编辑器右侧「详情」页签内容，形态对齐官方默认编辑器的 information 页签：
 * 灰底圆角卡片，顶部「标签 + 角标图标」，下方数值；无值条目不渲染卡片
 * （官方同款——发布前没有发布时间 / 链接，就不显示对应卡片）。
 * 布局同官方：两列网格，half 条目（字符数 / 词数等短项）一行两列，
 * 其余条目跨两列独占整行。条目与图标由页面计算后传入（Console / UC 数据源不同）。
 */
defineProps<{ items: PostDetailItem[] }>()
</script>

<template>
  <div class="bbs-detail">
    <template v-for="item in items" :key="item.label">
      <div
        v-if="item.value"
        class="bbs-detail__card"
        :class="{ 'bbs-detail__card--half': item.half }"
      >
        <div class="bbs-detail__head">
          <span class="bbs-detail__label">{{ item.label }}</span>
          <span v-if="item.icon" class="bbs-detail__chip">
            <component :is="item.icon" class="bbs-detail__icon" />
          </span>
        </div>
        <a
          v-if="item.href"
          :href="item.href"
          :title="item.value"
          target="_blank"
          rel="noopener"
          class="bbs-detail__link"
        >
          {{ item.value }}
        </a>
        <div v-else class="bbs-detail__value">{{ item.value }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 色值逐一对齐官方：卡片底 gray-100、角标底 gray-200、标签 gray-500、值 gray-900 */
/* 两列网格（官方 grid-cols-2 gap-2）：half 卡片占一列，其余跨两列整行 */
.bbs-detail {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
}

.bbs-detail__card {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  grid-column: span 2;
  border-radius: 6px;
  background: #f3f4f6;
  padding: 0.25rem 0.375rem;
  transition: var(--bbs-transition);
}

.bbs-detail__card--half {
  grid-column: span 1;
}

.bbs-detail__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.bbs-detail__label {
  color: #6b7280;
  font-size: 0.875rem;
  transition: var(--bbs-transition);
}

.bbs-detail__card:hover .bbs-detail__label {
  color: #111827;
}

.bbs-detail__chip {
  display: inline-flex;
  border-radius: var(--bbs-radius);
  background: #e5e7eb;
  padding: 0.125rem;
  transition: var(--bbs-transition);
}

.bbs-detail__card:hover .bbs-detail__chip {
  background: #fff;
}

.bbs-detail__icon {
  width: 1rem;
  height: 1rem;
  color: #4b5563;
  transition: var(--bbs-transition);
}

.bbs-detail__card:hover .bbs-detail__icon {
  color: #111827;
}

.bbs-detail__value {
  overflow-wrap: anywhere;
  color: #111827;
  font-size: 1rem;
  font-weight: 500;
}

/* 官方同款链接：正文字号小一号，hover 转蓝（blue-600） */
.bbs-detail__link {
  overflow-wrap: anywhere;
  color: #111827;
  font-size: 0.875rem;
  text-decoration: none;
  transition: var(--bbs-transition);
}

.bbs-detail__link:hover {
  color: #2563eb;
}
</style>
