package com.timxs.bbs.service;

/**
 * 版主回复评论请求体（对齐官方回复创建弹窗的语义）。
 *
 * @param raw       回复原文（必填；服务端净化后写入 raw 与 content）
 * @param quoteReply 被引用的回复名（楼中楼 @），可空
 * @author Tim0x0
 */
public record ReplyCreateParam(String raw, String quoteReply) {
}
