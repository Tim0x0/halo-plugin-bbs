import { FormType, stores, utils } from '@halo-dev/ui-shared'
import ShortUniqueId from 'short-unique-id'
import { slugify } from 'transliteration'
import { computed, watch, type Ref } from 'vue'

/**
 * 别名（slug）生成，逐条对齐 Halo 官方 `console-src/composables/use-slugify.ts`。
 *
 * 官方那份在应用层、未随包发布，插件取不到，故照其实现搬过来。行为要点：
 *
 * 1. **只在新建时随标题联想**——`auto` 由调用方传 `computed(() => !isUpdateMode)`。
 *    编辑已有内容时改标题不动 slug，否则改个错别字就把已发布的链接改了。
 * 2. **生成策略跟随站点设置** `globalInfo.postSlugGenerationStrategy`，
 *    与官方一致地只对帖子生效；分类等其它类型固定用标题音译。
 * 3. 随机类策略（shortUUID / UUID / timestamp）已有值时不覆盖，除非显式强制
 *    （手动点「重新生成」按钮时 `forceUpdate = true`）。
 */
const uid = new ShortUniqueId()

type SlugStrategy = (value?: string) => string

const strategies: Record<string, SlugStrategy> = {
  generateByTitle: (value?: string) => slugify(value || '', { trim: true }),
  shortUUID: () => uid.randomUUID(8),
  UUID: () => utils.id.uuid(),
  timestamp: () => new Date().getTime().toString(),
}

/** 这些策略与标题无关，已有值就别再改（换个标题不该换掉随机别名） */
const onceStrategies = new Set(['shortUUID', 'UUID', 'timestamp'])

export function useSlugify(
  source: Ref<string>,
  target: Ref<string>,
  auto: Ref<boolean>,
  formType: FormType
) {
  const globalInfoStore = stores.globalInfo()

  const currentStrategy = computed(
    () => globalInfoStore.globalInfo?.postSlugGenerationStrategy || 'generateByTitle'
  )

  function generateSlug(value: string): string {
    const strategy =
      formType === FormType.POST
        ? strategies[currentStrategy.value]
        : strategies.generateByTitle
    return (strategy || strategies.generateByTitle)(value)
  }

  function handleGenerateSlug(forceUpdate = false) {
    // once 判断必须看「这次实际会用的策略」：分类固定 generateByTitle，
    // 不能拿站点帖子策略（UUID / shortUUID）把分类别名锁死在首字符。
    const effective =
      formType === FormType.POST ? currentStrategy.value : 'generateByTitle'
    if (!forceUpdate && onceStrategies.has(effective) && target.value) {
      return
    }
    target.value = generateSlug(source.value)
  }

  watch(
    source,
    () => {
      if (auto.value) {
        // 不传 force：随机策略已有值时保持，避免每敲一个标题字符换新 UUID
        handleGenerateSlug(false)
      }
    },
    { immediate: true }
  )

  return { handleGenerateSlug }
}
