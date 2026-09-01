<script setup lang="ts">
/**
 * Console 帖子历史版本页（官方 PostSnapshots.vue 的 BBS 等价物）：
 * 薄包装，按 mode 分派 Console API；形态与交互见 shared/PostSnapshotsView.vue。
 */
import PostSnapshots from '@/shared/PostSnapshotsView.vue'
</script>

<template>
  <PostSnapshots mode="console" />
</template>
