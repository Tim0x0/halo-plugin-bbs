<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { axiosInstance, ucApiClient } from '@halo-dev/api-client'
import type { AxiosRequestConfig } from 'axios'
import {
  VButton,
  VModal,
  VSpace,
  Toast,
  IconSettings,
  IconSendPlaneFill,
} from '@halo-dev/components'
import PostEditorFrame from '@/shared/PostEditorFrame.vue'
import PostSettingForm from '@/shared/PostSettingForm.vue'
import { ucApi } from '@/api/bbs'
import { slugify } from 'transliteration'
import { defaultPostForm, type PostRequest } from '@/types/bbs'

/**
 * UC 发帖/编辑页：共用 PostEditorFrame + PostSettingForm（managed=false，
 * 无类型/置顶特权字段），走 UC API，发布即生效。
 */
const route = useRoute()
const router = useRouter()
const editName = ref<string>((route.query.name as string) || '')
const saving = ref(false)
const settingVisible = ref(false)
const categories = ref<{ label: string; value: string }[]>([])

const formData = ref(defaultPostForm())

// 未保存离开防护：对比快照，有改动时拦截刷新/关闭
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
    // 走公开接口取启用中的分类（UC 用户无 Console 权限）；子分类 label 带父级前缀
    const { data } = await axiosInstance.get<
      { name: string; displayName: string; parent?: { displayName: string } }[]
    >('/apis/api.bbs.timxs.com/v1alpha1/categories')
    categories.value = data.map((c) => ({
      label: c.parent ? `${c.parent.displayName} / ${c.displayName}` : c.displayName,
      value: c.name,
    }))
  } catch {
    /* 忽略：分类加载失败不阻塞发帖 */
  }
}

async function loadPost(name: string) {
  try {
    const { data: post } = await ucApi.getMine(name)
    if (post.locked) {
      Toast.warning('该帖子已被锁定，无法编辑')
      router.push({ name: 'BbsUcPosts' })
      return
    }
    formData.value = {
      title: post.title,
      slug: post.slug || '',
      // 回填真实类型：讨论 / 问答可互改；公告在表单中锁定展示（后端亦强制保持）
      type: post.type || 'POST',
      categoryName: post.category?.name || '',
      autoExcerpt: !post.excerpt,
      excerpt: post.excerpt || '',
      content: post.content || '',
      allowComment: post.allowComment !== false,
      pinned: false,
      pinPriority: 0,
    }
  } catch {
    Toast.error('加载帖子失败')
  }
}

function buildBody(): PostRequest {
  const f = formData.value
  return {
    title: f.title,
    // 留空时按标题生成拼音别名（对标 Halo 官方 use-slugify），避免落到后端中文兜底
    slug: f.slug.trim() || slugify(f.title, { trim: true }),
    type: f.type,
    categoryName: f.categoryName,
    excerpt: f.autoExcerpt ? '' : f.excerpt,
    content: f.content,
    allowComment: f.allowComment,
  }
}

async function handleUploadImage(file: File, options?: AxiosRequestConfig) {
  const { data } = await ucApiClient.storage.attachment.uploadAttachmentForUc({ file }, options)
  return data
}

async function doSave() {
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
      const { data } = await ucApi.update(editName.value, buildBody())
      Toast.success(data.spec?.phase === 'PENDING' ? '已保存，等待管理员审核' : '已保存')
    } else {
      const { data } = await ucApi.create(buildBody())
      Toast.success(data.spec?.phase === 'PENDING' ? '已提交，等待管理员审核' : '已发布')
    }
    markSaved()
    settingVisible.value = false
    router.push({ name: 'BbsUcPosts' })
  } catch {
    /* 请求错误由全局拦截器提示 */
  } finally {
    saving.value = false
  }
}

// 顶部「发布」：新建先弹设置补全分类/摘要，编辑已有直接保存（与 Console 编辑页对齐）
function onPublishClick() {
  if (editName.value) {
    doSave()
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
        <VButton size="sm" @click="settingVisible = true">
          <template #icon><IconSettings /></template>
          设置
        </VButton>
        <VButton type="secondary" :loading="saving" @click="onPublishClick">
          <template #icon><IconSendPlaneFill /></template>
          {{ editName ? '保存' : '发布' }}
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
    <PostSettingForm v-model="formData" :categories="categories" :managed="false" />
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" @click="doSave">
          {{ editName ? '保存' : '发布' }}
        </VButton>
        <VButton @click="settingVisible = false">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
