/**
 * 字符串 → 稳定 HEX，对齐 Flarum stringToColor：
 * UTF-16 码元累加 % 360 作色相，再转 HSV。
 *
 * 后台仅用于新建分类的预填色（0.55/0.75）。用户字母头像底色由前台 bbs.js 派生。
 */

function hsvToHex(h: number, s: number, v: number): string {
  const i = Math.floor(h * 6)
  const f = h * 6 - i
  const p = v * (1 - s)
  const q = v * (1 - f * s)
  const t = v * (1 - (1 - f) * s)
  let r: number
  let g: number
  let b: number
  switch (i % 6) {
    case 0:
      r = v
      g = t
      b = p
      break
    case 1:
      r = q
      g = v
      b = p
      break
    case 2:
      r = p
      g = v
      b = t
      break
    case 3:
      r = p
      g = q
      b = v
      break
    case 4:
      r = t
      g = p
      b = v
      break
    default:
      r = v
      g = p
      b = q
  }
  const hex = (n: number) => Math.floor(n * 255).toString(16).padStart(2, '0')
  return `#${hex(r)}${hex(g)}${hex(b)}`
}

function hueFromSeed(seed: string): number {
  let num = 0
  for (let i = 0; i < seed.length; i++) {
    num += seed.charCodeAt(i)
  }
  // 与 Java Math.floorMod 对齐：JS % 对负数会得到负余数
  return ((num % 360) + 360) % 360
}

/** 分类按名称生成的实色（仅新建预填 / 手动刷新时写入 spec.color）。 */
export function categoryColorFromName(name: string): string {
  const seed = name.trim()
  if (!seed) {
    return ''
  }
  return hsvToHex(hueFromSeed(seed) / 360, 0.55, 0.75)
}
