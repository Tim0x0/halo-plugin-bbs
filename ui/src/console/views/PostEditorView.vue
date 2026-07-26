<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { consoleApiClient } from '@halo-dev/api-client'
import type { AxiosRequestConfig } from 'axios'
import {
  VButton,
  VModal,
  VSpace,
  Toast,
  IconSave,
  IconEye,
  IconSettings,
  IconSendPlaneFill,
} from '@halo-dev/components'
import PostEditorFrame from '@/shared/PostEditorFrame.vue'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import { consoleApi } from '@/api/bbs'
import { defaultPostForm, type PostRequest } from '@/types/bbs'

/**
 * Console 帖子/公告编辑页：共用 PostEditorFrame + PostSettingForm（managed=true，
 * 可设类型与置顶），走 Console API。
 */
const route = useRoute()
const router = useRouter()
const editName = ref<string>((route.query.name as string) || '')
const saving = ref(false)
const settingVisible = ref(false)
const categories = ref<{ label: string; value: string }[]>([])

const formData = ref(defaultPostForm())
// 当前帖子状态（编辑已发布帖时按钮文案为「保存」而非「保存草稿」）
const loadedPhase = ref<string>('DRAFT')

// 未保存离开防护（对齐官方编辑器）：对比快照，有改动时拦截刷新/关闭
let savedSnapshot = JSON.stringify(formData.value)

function markSaved() {
  savedSnapshot = JSON.stringify(formData.value)
}

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (JSON.stringify(formData.value) !== savedSnapshot) {
    e.preventDefault()
    e.returnValue = ''
  }
}

async function fetchCategories() {
  try {
    const { data } = await consoleApi.listCategories()
    categories.value = (data || []).map((c) => ({ label: c.displayName, value: c.name }))
  } catch {
    /* 忽略：分类加载失败不阻塞编辑 */
  }
}

async function loadPost(name: string) {
  try {
    const { data: post } = await consoleApi.getPost(name)
    formData.value = {
      title: post.title,
      slug: post.slug || '',
      type: post.type || 'POST',
      categoryName: post.category?.name || '',
      autoExcerpt: !post.excerpt,
      excerpt: post.excerpt || '',
      content: post.content || '',
      pinned: !!post.pinned,
      pinPriority: post.pinPriority || 0,
    }
    loadedPhase.value = post.phase || 'DRAFT'
  } catch {
    Toast.error('加载帖子失败')
  }
}

function buildBody(): PostRequest {
  const f = formData.value
  return {
    title: f.title,
    slug: f.slug,
    type: f.type,
    categoryName: f.categoryName,
    excerpt: f.autoExcerpt ? '' : f.excerpt,
    content: f.content,
    pinned: f.pinned,
    pinPriority: f.pinPriority,
  }
}

async function handleUploadImage(file: File, options?: AxiosRequestConfig) {
  const { data } = await consoleApiClient.storage.attachment.uploadAttachmentForConsole(
    { file },
    options
  )
  return data
}

async function doSave(publish: boolean) {
  const f = formData.value
  if (!f.title.trim()) {
    Toast.warning('请输入标题')
    return
  }
  if (!f.content) {
    Toast.warning('请输入正文')
    return
  }
  saving.value = true
  try {
    if (editName.value) {
      await consoleApi.updatePost(editName.value, buildBody())
      if (publish) {
        await consoleApi.publishPost(editName.value)
        loadedPhase.value = 'PUBLISHED'
      }
      markSaved()
      settingVisible.value = false
      Toast.success(publish ? '已发布' : '已保存')
      if (publish) {
        router.push({ name: 'BbsPosts' })
      }
    } else {
      const { data } = await consoleApi.createPost(buildBody(), publish)
      markSaved()
      settingVisible.value = false
      if (publish) {
        Toast.success('已发布')
        router.push({ name: 'BbsPosts' })
      } else {
        // 首次保存草稿后转为编辑态并留在编辑器继续写（对齐官方文章编辑器）
        Toast.success('已保存草稿')
        editName.value = data.metadata.name
        loadedPhase.value = 'DRAFT'
        router.replace({ name: 'BbsPostEditor', query: { name: data.metadata.name } })
      }
    }
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    saving.value = false
  }
}

function preview() {
  if (formData.value.slug) {
    window.open(`/bbs/post/${formData.value.slug}`, '_blank')
  }
}

// 顶部「发布」：新建先弹设置补全分类/类型，编辑已有直接发布（对标官方文章编辑器）
function onPublishClick() {
  if (editName.value) {
    doSave(true)
  } else {
    settingVisible.value = true
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', onBeforeUnload)
  await fetchCategories()
  if (editName.value) {
    await loadPost(editName.value)
  }
  markSaved()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<template>
  <PostEditorFrame
    v-model:title="formData.title"
    v-model:raw="formData.content"
    :page-title="editName ? '编辑帖子' : '写帖子'"
    :upload-image="handleUploadImage"
  >
    <template #actions>
      <VSpace>
        <VButton v-if="editName" size="sm" @click="preview">
          <template #icon><IconEye /></template>
          预览
        </VButton>
        <VButton size="sm" :loading="saving" @click="doSave(false)">
          <template #icon><IconSave /></template>
          {{ editName && loadedPhase === 'PUBLISHED' ? '保存' : '保存草稿' }}
        </VButton>
        <VButton size="sm" @click="settingVisible = true">
          <template #icon><IconSettings /></template>
          设置
        </VButton>
        <VButton type="secondary" :loading="saving" @click="onPublishClick">
          <template #icon><IconSendPlaneFill /></template>
          发布
        </VButton>
      </VSpace>
    </template>
  </PostEditorFrame>

  <VModal
    v-if="settingVisible"
    title="帖子设置"
    :width="680"
    mount-to-body
    @close="settingVisible = false"
  >
    <PostSettingForm v-model="formData" :categories="categories" :managed="true" />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" @click="doSave(true)">发布</VButton>
        <VButton :loading="saving" @click="doSave(false)">保存草稿</VButton>
        <VButton @click="settingVisible = false">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
