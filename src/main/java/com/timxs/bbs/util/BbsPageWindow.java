package com.timxs.bbs.util;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 前台分页页码窗口：首页 + 末页 + 当前页 ±2（WordPress {@code mid_size=2} 档，
 * 论坛 / CMS 主流默认），中间空档以 null（省略号）占位。
 * 模板按序渲染即可，不必再做跳跃判断。
 *
 * @author Tim0x0
 */
public final class BbsPageWindow {

    private BbsPageWindow() {
    }

    /**
     * @param current 当前页；越界自动钳进 [1, total]
     * @param total 总页数；≤ 0 返回空表
     * @return 页码序列；null 元素 = 省略号
     */
    public static List<Integer> window(int current, int total) {
        if (total <= 0) {
            return List.of();
        }
        int c = Math.max(1, Math.min(current, total));
        TreeSet<Integer> pages = new TreeSet<>();
        pages.add(1);
        pages.add(total);
        for (int p = c - 2; p <= c + 2; p++) {
            if (p >= 1 && p <= total) {
                pages.add(p);
            }
        }
        List<Integer> out = new ArrayList<>();
        Integer prev = null;
        for (int p : pages) {
            if (prev != null && p - prev > 1) {
                out.add(null);
            }
            out.add(p);
            prev = p;
        }
        return out;
    }

    /** 总页数：向上取整；size 非法返回 0。 */
    public static int totalPages(long total, int size) {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
