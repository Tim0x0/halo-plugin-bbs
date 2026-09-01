/**
 * 帖子类型 / 状态的中文口径：列表筛选、状态列与编辑器详情面板共用，
 * 避免同一概念在不同页面漂移出不同措辞。
 */

export const BBS_TYPE_LABELS: Record<string, string> = {
  ANNOUNCEMENT: '公告',
  POST: '讨论',
  QUESTION: '问答',
}

// 对齐官方口径：DRAFT 对外一律显示「未发布」（官方 zh-CN.json 的
// core.post.filters.status.items.draft 即「未发布」），不区分是否曾经发布过。
// 内部标识（Phase.DRAFT / draft 字段）不变。
export const BBS_PHASE_LABELS: Record<string, string> = {
  DRAFT: '未发布',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
}

/** 主状态文字；已发布帖附带修改稿子状态（修改待审 / 修改驳回）。 */
export function bbsStatusText(phase?: string, draftPhase?: string): string {
  const main = BBS_PHASE_LABELS[phase || ''] || BBS_PHASE_LABELS.DRAFT
  if (phase === 'PUBLISHED') {
    if (draftPhase === 'PENDING') {
      return `${main}（修改待审）`
    }
    if (draftPhase === 'REJECTED') {
      return `${main}（修改驳回）`
    }
  }
  return main
}

/** 类型单选 / 下拉选项：按传入顺序生成，文案取自统一口径。 */
export function typeOptions(...values: string[]): { label: string; value: string }[] {
  return values.map((value) => ({ label: BBS_TYPE_LABELS[value] ?? value, value }))
}
