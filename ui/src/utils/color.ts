/**
 * 进入样式 / 落库的色值清洗：只放行 HEX（含透明通道）。
 * 空白或非法返回空串。
 */
export function sanitizeHex(color?: string): string {
  const c = (color || '').trim()
  return /^#([a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})$/.test(c)
    ? c
    : ''
}
