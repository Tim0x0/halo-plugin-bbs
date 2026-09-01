<script setup lang="ts">
/**
 * UC 帖子历史版本页：薄包装，按 mode 分派 UC API（归属校验后的聚合接口）；
 * 形态与交互见 shared/PostSnapshotsView.vue。
 */
import PostSnapshots from '@/shared/PostSnapshotsView.vue'
</script>

<template>
  <PostSnapshots mode="uc" />
</template>
