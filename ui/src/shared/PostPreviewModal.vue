<script lang="ts" setup>
/**
 * 预览弹窗：对齐官方 UrlPreviewModal——全屏 VModal + 桌面 / 平板 / 手机三档视口
 * 切换（iframe 限宽模拟），右上角外链可另开标签页。预览地址由调用方传入
 * （BBS 为 /bbs/preview/{name}）。
 */
import { computed, markRaw, ref } from 'vue'
import {
  IconComputer,
  IconLink,
  IconPhone,
  IconTablet,
  VLoading,
  VModal,
  VTabbar,
} from '@halo-dev/components'

const props = withDefaults(
  defineProps<{
    title?: string
    url?: string
  }>(),
  { title: undefined, url: '' }
)

const emit = defineEmits<{ (event: 'close'): void }>()

const devices = [
  { id: 'desktop', icon: markRaw(IconComputer) },
  { id: 'tablet', icon: markRaw(IconTablet) },
  { id: 'phone', icon: markRaw(IconPhone) },
]
const activeDevice = ref(devices[0].id)

const iframeClasses = computed(() => {
  if (activeDevice.value === 'desktop') {
    return 'w-full h-full'
  }
  if (activeDevice.value === 'tablet') {
    return 'w-2/3 h-2/3 ring-2 rounded ring-gray-300'
  }
  return 'w-96 h-[50rem] ring-2 rounded ring-gray-300'
})
</script>

<template>
  <VModal
    :body-class="['!p-0']"
    fullscreen
    :title="title"
    :layer-closable="true"
    @close="emit('close')"
  >
    <template #center>
      <VTabbar v-model:active-id="activeDevice" :items="devices as any" type="outline" />
    </template>
    <template #actions>
      <span>
        <a :href="url" target="_blank">
          <IconLink />
        </a>
      </span>
    </template>
    <div class="flex h-full items-center justify-center">
      <VLoading v-if="!url" />
      <iframe
        v-else
        class="border-none transition-all duration-500"
        :class="iframeClasses"
        :src="url"
      ></iframe>
    </div>
  </VModal>
</template>
