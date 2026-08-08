package com.timxs.bbs.integration;

import com.timxs.bbs.query.BbsQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * interaction-plus 集成装配：仅当其 api 类在 classpath（已安装并建立可选依赖的类加载
 * 委托）时才注册集成 bean，缺席时本插件照常启动。
 *
 * <p>安全要点（缺一即在 interaction-plus 缺席时 NoClassDefFoundError）：</p>
 * <ul>
 *   <li>{@code @ConditionalOnClass} 用 {@code name} 字符串形式——注解值不触发类加载，
 *       条件评估经 Halo 为插件容器专门设置的 beanClassLoader 探测（官方支持路径）；</li>
 *   <li>@Bean 方法返回类型必须是 {@code Object}——方法签名出现 api 类型会在
 *       Spring 反射读取方法元数据时触发类加载；方法体字节码是懒解析的，
 *       条件不满足则方法不执行、{@link BbsUserStatContributor} 永不被加载；</li>
 *   <li>本类自身不得 implements / 字段引用任何 interaction-plus 类型。</li>
 * </ul>
 *
 * @author Tim0x0
 */
@Component
public class InteractionPlusIntegration {

    /** 统计贡献实现（发帖数）：实际类型为 BbsUserStatContributor，供 ExtensionGetter 按接口发现。 */
    @Bean
    @ConditionalOnClass(name = "com.timxs.interactionplus.api.UserStatContributor")
    public Object bbsUserStatContributor(BbsQueryService queryService) {
        return new BbsUserStatContributor(queryService);
    }
}
