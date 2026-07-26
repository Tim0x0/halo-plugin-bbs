package com.timxs.bbs.vo;

import lombok.Builder;
import lombok.Data;
import run.halo.app.core.extension.User;

/**
 * 帖子作者 VO（内联展示属性：显示名 + 头像）。
 *
 * @author Tim0x0
 */
@Data
@Builder
public class OwnerVo {

    /** 用户名（User 的 metadata.name） */
    private String name;

    private String displayName;

    private String avatar;

    /** 由 User 扩展构建；user 为 null 时以 username 兜底显示。 */
    public static OwnerVo from(String username, User user) {
        if (user == null) {
            return OwnerVo.builder().name(username).displayName(username).build();
        }
        var spec = user.getSpec();
        return OwnerVo.builder()
                .name(username)
                .displayName(spec.getDisplayName() == null || spec.getDisplayName().isBlank()
                        ? username : spec.getDisplayName())
                .avatar(spec.getAvatar())
                .build();
    }
}
