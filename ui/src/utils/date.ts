/**
 * 时间展示包装：统一空值兜底口径，避免各组件各抄一行
 * （绝对 / 相对时间实现来自 @halo-dev/ui-shared）。
 */
import { utils } from '@halo-dev/ui-shared'

/** 绝对时间；空值返回空串（列表表格单元用）。 */
export function formatTime(value?: string): string {
  return value ? utils.date.format(value) : ''
}

/** 绝对时间；空值返回 '—'（详情 / 记录面板用）。 */
export function formatDate(value?: string): string {
  return value ? utils.date.format(value) : '—'
}

/** 相对时间；空值返回 '—'。 */
export function timeAgo(value?: string): string {
  return (value && utils.date.timeAgo(value)) || '—'
}
