import { watch, type Ref } from 'vue'
import { categoryColorFromName } from '@/utils/string-to-color'

/**
 * 分类色随名称联想，口径对齐 slug：
 * - 新建（auto=true）：打名称即重算，保存时写入 spec.color
 * - 编辑（auto=false）：改名不动色，避免改个错别字整站换皮；
 *   点「按名称重新生成」才覆盖
 */
export function useCategoryColor(
  source: Ref<string>,
  target: Ref<string>,
  auto: Ref<boolean>
) {
  function handleGenerateColor(forceUpdate = false) {
    if (!auto.value && !forceUpdate) {
      return
    }
    const next = categoryColorFromName(source.value)
    if (next) {
      target.value = next
    }
  }

  watch(
    source,
    () => {
      if (auto.value) {
        handleGenerateColor(false)
      }
    },
    { immediate: true }
  )

  return { handleGenerateColor }
}
