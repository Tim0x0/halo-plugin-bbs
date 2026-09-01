<script lang="ts" setup>
import { consoleApiClient, coreApiClient, type User } from '@halo-dev/api-client'
import {
  IconArrowDown,
  VAvatar,
  VDropdown,
  VEntity,
  VEntityContainer,
  VEntityField,
} from '@halo-dev/components'
import { refDebounced } from '@vueuse/core'
import { nextTick, ref, watch } from 'vue'

/**
 * 作者筛选下拉，对齐官方 `ui/src/components/filter/UserFilterDropdown.vue`。
 *
 * 官方那份在应用层、未随包发布，故照其结构搬来，并做四处适配：
 * - 数据请求改手写（vue-query 不在插件共享依赖里，见 DEC-1）；
 * - 聚焦改原生 DOM（官方 setFocus 是 Halo 内部工具，插件取不到）；
 * - 样式改 scoped CSS（不依赖宿主 Tailwind 是否恰好生成了对应 class）；
 * - 候选再排除 ghost / 删除中的用户（见 fetchUsers 注释）。
 */
const props = withDefaults(
  defineProps<{
    label: string
    modelValue?: string
  }>(),
  { modelValue: undefined }
)

const emit = defineEmits<{ (event: 'update:modelValue', value?: string): void }>()

const dropdown = ref()
const keyword = ref('')
const debouncedKeyword = refDebounced(keyword, 300)
const users = ref<User[]>([])
const selectedUser = ref<User | null>(null)

/** 已选中的作者：单独取一次，保证它即便不在当前搜索结果里也能显示名字 */
async function fetchSelectedUser() {
  if (!props.modelValue) {
    selectedUser.value = null
    return
  }
  try {
    const { data } = await coreApiClient.user.getUser({ name: props.modelValue })
    selectedUser.value = data
  } catch {
    selectedUser.value = null
  }
}

async function fetchUsers() {
  try {
    const { data } = await consoleApiClient.user.listUsers({
      // ghost 是 Halo 的系统占位用户（显示名「已删除用户」，承接被删用户的内容）。
      // BBS 帖子的 owner 是插件自己的字段，用户删除后不会改写到 ghost——
      // 把它列进候选，用户点了也只会得到空列表，故与 anonymousUser 一并排除
      fieldSelector: ['name!=anonymousUser', 'name!=ghost'],
      keyword: debouncedKeyword.value,
      page: 1,
      size: 30,
    })
    // 删除中（已打删除标记但终结器未跑完）的用户同样不进候选
    const pureUsers = (data?.items || [])
      .map((item) => item.user)
      .filter((user) => !user.metadata?.deletionTimestamp)
    if (!pureUsers.length) {
      users.value = selectedUser.value ? [selectedUser.value] : []
      return
    }
    // 选中项置顶，避免它被搜索结果挤掉后无法取消选择
    users.value = selectedUser.value
      ? [
          selectedUser.value,
          ...pureUsers.filter(
            (user) => user.metadata.name !== selectedUser.value?.metadata.name
          ),
        ]
      : pureUsers
  } catch {
    /* 忽略：候选加载失败不阻塞筛选器本身 */
  }
}

watch(debouncedKeyword, fetchUsers)
watch(
  () => props.modelValue,
  async () => {
    await fetchSelectedUser()
    await fetchUsers()
  },
  { immediate: true }
)

function handleSelect(user: User) {
  const name = user.metadata?.name
  // 再次点击已选项 = 取消筛选（官方行为）
  emit('update:modelValue', name === props.modelValue ? undefined : name)
  dropdown.value?.hide()
}

function onDropdownShow() {
  // 等 popper 挂载完再聚焦；取不到元素就静默跳过
  setTimeout(() => {
    nextTick(() => {
      const input = document.getElementById('userFilterDropdownInput')
      if (input instanceof HTMLInputElement) {
        input.focus()
      }
    })
  }, 200)
}
</script>

<template>
  <VDropdown ref="dropdown" popper-class="bbs-panel-popper" @show="onDropdownShow">
    <div class="bbs-filter-trigger" :class="{ 'bbs-filter-trigger--active': modelValue !== undefined }">
      <span class="bbs-filter-trigger__label">
        {{ selectedUser ? `${label}：${selectedUser.spec.displayName}` : label }}
      </span>
      <IconArrowDown />
    </div>
    <template #popper>
      <div class="bbs-filter-panel">
        <div class="bbs-filter-panel__search">
          <FormKit
            id="userFilterDropdownInput"
            v-model="keyword"
            placeholder="搜索"
            type="text"
          />
        </div>
        <VEntityContainer>
          <VEntity
            v-for="user in users"
            :key="user.metadata.name"
            :is-selected="modelValue === user.metadata.name"
            @click="handleSelect(user)"
          >
            <template #start>
              <VEntityField>
                <template #description>
                  <VAvatar
                    :key="user.metadata.name"
                    :alt="user.spec.displayName"
                    :src="user.spec.avatar"
                    size="md"
                  />
                </template>
              </VEntityField>
              <VEntityField
                :title="user.spec.displayName"
                :description="user.metadata.name"
              />
            </template>
          </VEntity>
        </VEntityContainer>
      </div>
    </template>
  </VDropdown>
</template>

<!-- bbs-filter-trigger / bbs-filter-panel 系列见 styles/tokens.css -->
