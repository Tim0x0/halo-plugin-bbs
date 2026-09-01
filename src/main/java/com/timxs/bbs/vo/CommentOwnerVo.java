package com.timxs.bbs.vo;

import java.util.Map;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;

/**
 * 评论 / 回复作者的统一 VO 形态，以及防泄露策略的唯一实现
 * （公开只读 {@code RoCommentVo} 与管理面 {@code BbsCommentAdminVo} 共用）。
 *
 * <p>仅 User kind 回 {@code name}（username，供 hip-user-avatar 拉装扮）；
 * Email kind（匿名评论）的 owner.name 是邮箱，绝不能下发。昵称 / 头像以 User 表为准
 * （owner 里的是创建时快照，改名后会过时）。无头像字母 / 底色由前台按
 * {@code displayName} 哈希派生，本 VO 不下发。</p>
 *
 * @author Tim0x0
 */
@Data
@Builder
public class CommentOwnerVo {

    /** User kind=username（供 hip-user-avatar）；Email kind=null */
    private String name;

    private String displayName;

    private String avatar;

    /** User / Email */
    private String kind;

    /** 按统一策略映射；{@code users} 为按 username 预取的 User 字典（可空）。 */
    public static CommentOwnerVo from(Comment.CommentOwner owner, Map<String, User> users) {
        String username = (owner != null && "User".equals(owner.getKind()))
                ? owner.getName() : null;
        var user = username != null ? users.get(username) : null;
        String displayName;
        if (user != null && StringUtils.isNotBlank(user.getSpec().getDisplayName())) {
            displayName = user.getSpec().getDisplayName();
        } else if (owner != null && StringUtils.isNotBlank(owner.getDisplayName())) {
            displayName = owner.getDisplayName();
        } else {
            // Email kind 的 owner.name 是邮箱，绝不能当下发昵称
            displayName = "匿名";
        }
        return CommentOwnerVo.builder()
                .name(username)
                .displayName(displayName)
                .avatar(user != null ? user.getSpec().getAvatar() : null)
                .kind(owner != null ? owner.getKind() : null)
                .build();
    }
}
