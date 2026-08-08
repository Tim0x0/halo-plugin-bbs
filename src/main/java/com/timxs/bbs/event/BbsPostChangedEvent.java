package com.timxs.bbs.event;

import org.springframework.context.ApplicationEvent;
import run.halo.app.plugin.SharedEvent;

/**
 * BBS 帖子变更事件：发布 / 编辑 / 删除 / 审核状态变更 / 置顶等任何帖子改动均触发。
 *
 * <p>{@link SharedEvent} 使其跨插件可见——供 plugin-feed 集成层监听以清理 RSS 缓存
 * （{@code /feed/bbs/posts.xml}），避免订阅端最长 1 小时（plugin-feed 缓存 TTL）的更新延迟。</p>
 *
 * <p>不携带 payload：仅作变更信号；消费方按需自行清缓存或重取，无需区分具体改动类型。</p>
 *
 * @author Tim0x0
 */
public class BbsPostChangedEvent extends ApplicationEvent {

    public BbsPostChangedEvent(Object source) {
        super(source);
    }
}
