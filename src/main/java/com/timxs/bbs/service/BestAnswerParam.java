package com.timxs.bbs.service;

/**
 * 设 / 取消最佳答案。{@code commentName} 空表示取消。
 *
 * @param commentName 顶层评论 metadata.name，可空
 * @author Tim0x0
 */
public record BestAnswerParam(String commentName) {
}
