<script setup lang="ts">
/**
 * Console/UC 分类标识：有 iconSvg 显示图标，否则色点；名称可选。
 *
 * 两级分类的呈现（R12）：父级作为**弱化前缀**跟在同一行里（`父分类 /` 用更淡的
 * 灰、子分类名用正常色），而不是缩进树、也不是等权重的「父分类 / 子分类」长串。
 * - 等权重长串读起来是一坨，扫不出主次；
 * - 缩进树在两级封顶的场景下是杀鸡用牛刀，且下拉里两行左缘容易对不齐。
 * 弱化前缀既保住了「这是谁家的子分类」，又让视线先落在分类名本身。
 *
 * R5：仅后台列表使用，前台 /bbs 不同步。
 */
withDefaults(
  defineProps<{
    displayName?: string
    /** 分类色：只给无图标时的色点；空则灰点 */
    color?: string
    /** 图标 SVG：颜色自带（选色已烤进 fill，未选色为 currentColor 随文字色） */
    iconSvg?: string
    /** 父分类名；有值时作为弱化前缀显示 */
    parentName?: string
    /** 无分类占位 */
    empty?: boolean
    /**
     * 图标盒尺寸（px），默认 10 = description 行 / 下拉里的次要标识。
     * 分类管理页给 14：那一行分类名是 14px 主标题，图标是这行的身份而非装饰，
     * 10px 既压不住也看不清自选 SVG 的细节（前台左栏同一批图标是 15px）。
     * 色点不跟着放大（见样式注释）。
     */
    size?: number
  }>(),
  {
    empty: false,
    size: 10,
  }
)
</script>

<template>
  <span
    class="cat-mark"
    :class="{ 'cat-mark--empty': empty }"
    :style="{ '--cat-mark-size': `${size}px` }"
  >
    <span
      v-if="!empty && iconSvg"
      class="cat-mark__icon"
      v-html="iconSvg"
    />
    <span
      v-else
      class="cat-mark__dot"
      :style="!empty && color ? { background: color } : undefined"
    />
    <span class="cat-mark__name">
      <slot>
        <template v-if="empty">未分类</template>
        <template v-else>
          <span v-if="parentName" class="cat-mark__parent">{{ parentName }} /</span>
          {{ displayName }}
        </template>
      </slot>
    </span>
  </span>
</template>

<style scoped>
.cat-mark {
  /* 兜底：prop 未传时等同旧值 10px */
  --cat-mark-size: 10px;
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  min-width: 0;
}

.cat-mark--empty {
  color: var(--bbs-text-faint, #9ca3af);
}

/* 色点恒定 10px，不跟 --cat-mark-size 放大：放大后成一块明显色块，会抢掉分类名
   的注意力。改用左右 margin 把占位补到图标同宽，让有图标/无图标两行的文字左缘
   对齐（与前台左栏 .bbs-nav__dot 同一手法）。size=10 时 margin 算出来是 0。 */
.cat-mark__dot {
  width: 10px;
  height: 10px;
  margin: 0 calc((var(--cat-mark-size) - 10px) / 2);
  border-radius: 2px;
  flex: none;
  background: var(--bbs-border, #e5e7eb);
}

.cat-mark__icon {
  width: var(--cat-mark-size);
  height: var(--cat-mark-size);
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.cat-mark__icon :deep(svg) {
  width: var(--cat-mark-size);
  height: var(--cat-mark-size);
  display: block;
}

.cat-mark__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 父级前缀：比分类名更淡一档，让视线先落在分类名上 */
.cat-mark__parent {
  color: var(--bbs-text-faint, #9ca3af);
  font-weight: 400;
}
</style>
