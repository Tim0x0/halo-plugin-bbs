/**
 * 正文统计：剥离 HTML 后，字符数=去空白的纯文本长度；
 * 词数=中文字数 + 西文/数字单词数（中文按字计，与主流编辑器口径一致）。
 */
export function contentStats(html: string): { chars: number; words: number } {
  if (!html) {
    return { chars: 0, words: 0 }
  }
  const text = html.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ')
  const noSpace = text.replace(/\s+/g, '')
  const chars = noSpace.length
  const cjk = (noSpace.match(/[一-鿿]/g) || []).length
  const words =
    cjk + (noSpace.replace(/[一-鿿]/g, ' ').match(/[A-Za-z0-9]+/g) || []).length
  return { chars, words }
}
