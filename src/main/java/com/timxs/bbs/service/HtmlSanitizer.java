package com.timxs.bbs.service;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

/**
 * HTML 净化工具，防止用户投稿内容中的 XSS。
 *
 * <p>Safelist 覆盖 Halo 2.25 官方富文本编辑器 ExtensionsKit 会持久化的结构，
 * 包括任务列表、图片/音视频、Figure、Iframe、折叠块、分栏、图库和可调整尺寸的表格。
 * URL、结构化属性和内联 CSS 会在 Jsoup 白名单之后继续逐项收窄，避免为了兼容编辑器而
 * 放开事件属性、任意协议或任意 CSS。Jsoup 由 {@code run.halo.app:api} 传递提供，插件
 * 无需额外声明依赖。</p>
 *
 * <p>采用「主动清洗」（{@code Jsoup.clean}）而非校验拒绝：脏 HTML 会被清理而非报错，
 * 对用户更友好。</p>
 *
 * @author Tim0x0
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("s", "strike", "del", "hr", "figure", "figcaption", "video", "audio",
                    "iframe", "details", "summary", "mark", "label", "input")
            .addAttributes(":all", "dir")
            .addAttributes("code", "class")
            .addAttributes("a", "target", "rel")
            .addAttributes("p", "style")
            .addAttributes("h1", "style")
            .addAttributes("h2", "style")
            .addAttributes("h3", "style")
            .addAttributes("h4", "style")
            .addAttributes("h5", "style")
            .addAttributes("h6", "style")
            .addAttributes("span", "style")
            .addAttributes("ol", "start", "data-tight")
            .addAttributes("ul", "data-type", "data-tight")
            .addAttributes("li", "data-type", "data-checked")
            .addAttributes("label", "aria-label")
            .addAttributes("input", "type", "checked")
            .addAttributes("pre", "data-params", "collapsed", "theme")
            .addAttributes("figure", "data-content-type", "style")
            .addAttributes("figcaption", "data-placeholder", "style")
            .addAttributes("mark", "data-color", "style")
            .addAttributes("div", "class", "cols", "index", "data-type",
                    "data-group-size", "data-layout", "data-gap", "data-aspect-ratio",
                    "style")
            .addAttributes("img", "href", "width", "height", "loading", "data-type",
                    "style")
            .addAttributes("video", "src", "width", "height", "autoplay", "controls",
                    "loop")
            .addAttributes("audio", "src", "autoplay", "controls", "loop")
            .addAttributes("iframe", "src", "width", "height", "scrolling", "frameborder",
                    "allowfullscreen", "framespacing", "style")
            .addAttributes("details", "open")
            .addAttributes("table", "style")
            .addAttributes("col", "width", "style")
            .addAttributes("td", "colspan", "rowspan", "colwidth", "data-colwidth", "align",
                    "style")
            .addAttributes("th", "colspan", "rowspan", "colwidth", "data-colwidth", "align",
                    "style")
            // 对齐 Halo/Tiptap Link 默认协议；这些都只落在 href，不会作为可执行脚本。
            .addProtocols("a", "href", "http", "https", "ftp", "ftps", "mailto", "tel",
                    "callto", "sms", "cid", "xmpp")
            .addProtocols("img", "src", "http", "https")
            .addProtocols("img", "href", "http", "https", "ftp", "ftps", "mailto", "tel",
                    "callto", "sms", "cid", "xmpp")
            .addProtocols("video", "src", "http", "https")
            .addProtocols("audio", "src", "http", "https")
            .addProtocols("iframe", "src", "http", "https")
            .preserveRelativeLinks(true);

    private static final Document.OutputSettings HTML_OUTPUT = new Document.OutputSettings()
            .prettyPrint(false);
    private static final String SANITIZER_BASE_URI = "https://halo.invalid/";

    private static final Pattern LANGUAGE_CLASS = Pattern.compile("language-[a-zA-Z0-9_+-]{1,64}");
    private static final Pattern SAFE_THEME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern INTEGER_LIST = Pattern.compile("[1-9][0-9]{0,4}(,[1-9][0-9]{0,4})*");
    private static final Pattern CSS_NUMBER = Pattern.compile("(?:0|[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private static final Pattern CSS_LENGTH = Pattern.compile(
            "(?:0|(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:px|em|rem|%|vw|vh|ch))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_COLOR = Pattern.compile(
            "(?:#[0-9a-fA-F]{3,8}|[a-zA-Z]{1,32}|"
                    + "(?:rgb|rgba|hsl|hsla)\\(\\s*[0-9.,% /+-]+\\s*\\)|"
                    + "var\\(--[a-zA-Z0-9_-]{1,64}\\))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FONT_FAMILY = Pattern.compile(
            "[\\p{L}\\p{N}\\s,'\"._-]{1,200}");
    private static final Pattern FLEX_VALUE = Pattern.compile(
            "(?:0|[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"
                    + "(?:\\s+(?:0|[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+|auto|none|"
                    + "(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)%)){0,2}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASPECT_RATIO = Pattern.compile(
            "(?:auto\\s+)?(?:0|[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"
                    + "(?:\\s*/\\s*(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))?",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> DISPLAY_VALUES = Set.of(
            "block", "inline", "inline-block", "flex", "inline-flex", "grid", "none");
    private static final Set<String> FLEX_DIRECTION_VALUES = Set.of(
            "row", "row-reverse", "column", "column-reverse");
    private static final Set<String> ALIGN_VALUES = Set.of(
            "start", "end", "center", "stretch", "baseline", "flex-start", "flex-end");
    private static final Set<String> JUSTIFY_VALUES = Set.of(
            "start", "end", "center", "flex-start", "flex-end", "space-between",
            "space-around", "space-evenly");
    private static final Set<String> TEXT_ALIGN_VALUES = Set.of(
            "start", "end", "left", "right", "center", "justify");
    private static final Set<String> OVERFLOW_VALUES = Set.of(
            "visible", "hidden", "clip", "scroll", "auto");

    /**
     * SVG 白名单：标签集对齐 DOMPurify 的 SVG 白名单，取 Iconify 图标集实际用到的子集。
     *
     * <p><b>不放行任何 URL 属性，也不放行带 URL 的标签</b>（use / image / a）—— {@code javascript:}
     * 无论明文还是 HTML 实体编码（{@code java&#115;cript:}）都没有落点；on* 事件属性同理
     * 不在白名单内，故 {@code animate} / {@code set} 等 SMIL 标签与 {@code foreignObject}
     * （HTML 逃逸口）一并拒之门外。解析式清洗不依赖属性分隔形态（空白 / 斜杠 / 换行），
     * 这是正则方案做不到的。</p>
     *
     * <p>安全面由「零 URL 属性 + 零事件属性」保证，与放行哪些形状标签无关，所以标签集
     * 可以按图标渲染需要给足。</p>
     */
    private static final Safelist SVG_SAFELIST = Safelist.none()
            .addTags("svg", "g", "defs", "path", "circle", "ellipse", "rect", "line",
                    "polyline", "polygon", "text", "tspan", "clipPath", "mask",
                    "linearGradient", "radialGradient", "stop", "title", "desc")
            // 几何属性：多个形状标签共用，必须给 :all 而非单个标签——尤其 width / height，
            // 只授给 svg 会让 <rect> 塌成 0×0（线框类图标的主体正是 rect）
            .addAttributes(":all", "d", "cx", "cy", "r", "rx", "ry", "x", "y", "dx", "dy",
                    "x1", "y1", "x2", "y2", "fx", "fy", "width", "height",
                    "points", "offset", "transform", "pathLength")
            // 着色与描边
            .addAttributes(":all", "fill", "fill-opacity", "fill-rule",
                    "stroke", "stroke-width", "stroke-opacity", "stroke-linecap",
                    "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset",
                    "stroke-miterlimit", "opacity", "paint-order", "vector-effect",
                    "stop-color", "stop-opacity")
            // 裁剪 / 遮罩 / 渐变：引用只能是 url(#id) 形式的文档内引用——外链引不到东西，
            // 因为承载外链的 use / image 未放行。放行这些标签是为了不留悬空引用：
            // 只留 clip-path 属性而剥掉 <clipPath> 定义，会让被裁切的元素整体不渲染
            .addAttributes(":all", "id", "class", "clip-path", "clip-rule", "mask",
                    "clipPathUnits", "maskUnits", "maskContentUnits",
                    "gradientUnits", "gradientTransform", "spreadMethod")
            // 文本型图标（少数图标集用 <text>）
            .addAttributes(":all", "font-family", "font-size", "font-weight", "font-style",
                    "text-anchor", "dominant-baseline", "letter-spacing")
            .addAttributes(":all", "aria-hidden", "aria-label", "role", "focusable")
            .addAttributes("svg", "xmlns", "viewBox", "preserveAspectRatio");

    /**
     * SVG 输出：关 pretty-print，避免调和器「脏了才写回」把换行缩进当成脏。
     * 输入仍走 HTML 解析（才能识别 {@code <svg/onload>} 这类 HTML 攻击形态）；
     * XML 序列化只影响自闭合写法，清洗结果对自身幂等。
     *
     * <p>注意：输出的自闭合形态是 {@code <path />}（带空格），与 Iconify 原始输出的
     * {@code <path/>} 不同，所以存量图标会被调和器规范化重写一次——一次性收敛，之后判定干净。</p>
     */
    private static final Document.OutputSettings SVG_OUTPUT = new Document.OutputSettings()
            .prettyPrint(false)
            .syntax(Document.OutputSettings.Syntax.xml);

    private HtmlSanitizer() {
    }

    /**
     * 清洗 HTML，移除不安全的标签/属性/脚本。
     *
     * @param html 原始 HTML，可为 null
     * @return 净化后的 HTML；入参为 null 时返回 null
     */
    public static String clean(String html) {
        if (html == null) {
            return null;
        }
        // Jsoup 需要一个带协议的 base URI 才能判定 /upload/... 这类 Halo 相对附件地址
        // 使用了合法协议；preserveRelativeLinks(true) 会保证输出仍是原始相对地址。
        var cleaned = Jsoup.clean(html, SANITIZER_BASE_URI, SAFELIST, HTML_OUTPUT);
        var document = Jsoup.parseBodyFragment(cleaned);
        document.outputSettings().prettyPrint(false);
        sanitizeRichText(document);
        return document.body().html();
    }

    private static void sanitizeRichText(Document document) {
        document.select("[style]").forEach(HtmlSanitizer::sanitizeStyle);
        document.select("[dir]").forEach(element -> retainEnumAttribute(
                element, "dir", Set.of("ltr", "rtl", "auto")));

        document.select("div[class]").forEach(element -> sanitizeClasses(
                element, Set.of("columns", "column"), null));
        document.select("code[class]").forEach(element -> sanitizeClasses(
                element, Set.of("hljs"), LANGUAGE_CLASS));

        document.select("a").forEach(HtmlSanitizer::sanitizeAnchor);
        document.select("ol[start]").forEach(element -> sanitizeIntegerAttribute(
                element, "start", 1, Integer.MAX_VALUE));
        document.select("ol[data-tight], ul[data-tight]").forEach(element ->
                sanitizeBooleanDataAttribute(element, "data-tight"));
        document.select("ul[data-type]").forEach(element -> retainExactAttribute(
                element, "data-type", Set.of("taskList")));
        document.select("li[data-type]").forEach(element -> retainExactAttribute(
                element, "data-type", Set.of("taskItem")));
        document.select("li[data-checked]").forEach(element ->
                sanitizeBooleanDataAttribute(element, "data-checked"));
        document.select("input").forEach(HtmlSanitizer::sanitizeTaskCheckbox);

        document.select("pre[theme]").forEach(element -> {
            if (!SAFE_THEME.matcher(element.attr("theme")).matches()) {
                element.removeAttr("theme");
            }
        });
        document.select("figure[data-content-type]").forEach(element ->
                retainEnumAttribute(element, "data-content-type",
                        Set.of("image", "video", "audio")));
        document.select("mark[data-color]").forEach(element -> {
            if (!CSS_COLOR.matcher(element.attr("data-color").strip()).matches()) {
                element.removeAttr("data-color");
            }
        });

        document.select("div[data-type]").forEach(HtmlSanitizer::sanitizeDivDataType);
        document.select("div[data-group-size]").forEach(element -> sanitizeIntegerAttribute(
                element, "data-group-size", 1, 12));
        document.select("div[data-layout]").forEach(element -> retainEnumAttribute(
                element, "data-layout", Set.of("auto", "square")));
        document.select("div[data-gap]").forEach(element -> sanitizeDecimalAttribute(
                element, "data-gap", 0, 100));
        document.select("div[data-aspect-ratio]").forEach(element -> sanitizeDecimalAttribute(
                element, "data-aspect-ratio", 0, 100));
        document.select("div[cols]").forEach(element -> sanitizeIntegerAttribute(
                element, "cols", 1, 12));
        document.select("div[index]").forEach(element -> sanitizeIntegerAttribute(
                element, "index", 0, 11));
        document.select("img[data-type]").forEach(element -> retainExactAttribute(
                element, "data-type", Set.of("gallery-image")));

        document.select("img[width], video[width], iframe[width], col[width]")
                .forEach(element -> sanitizeDimensionAttribute(element, "width"));
        document.select("img[height], video[height], iframe[height]")
                .forEach(element -> sanitizeDimensionAttribute(element, "height"));
        document.select("img[loading]").forEach(element -> retainEnumAttribute(
                element, "loading", Set.of("lazy", "eager")));
        document.select("iframe").forEach(HtmlSanitizer::sanitizeIframe);

        document.select("td, th").forEach(HtmlSanitizer::sanitizeTableCell);
    }

    private static void sanitizeStyle(Element element) {
        var declarations = element.attr("style").split(";");
        var safeDeclarations = Arrays.stream(declarations)
                .map(String::strip)
                .filter(declaration -> !declaration.isEmpty())
                .map(HtmlSanitizer::sanitizeStyleDeclaration)
                .filter(StringUtils::isNotBlank)
                .toList();
        if (safeDeclarations.isEmpty()) {
            element.removeAttr("style");
        } else {
            element.attr("style", String.join("; ", safeDeclarations) + ";");
        }
    }

    private static String sanitizeStyleDeclaration(String declaration) {
        var separator = declaration.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        var property = declaration.substring(0, separator).strip().toLowerCase(Locale.ROOT);
        if (property.equals("minwidth")) {
            // Halo 2.25 表格扩展在无固定列宽时会序列化出 minWidth。
            property = "min-width";
        }
        var value = declaration.substring(separator + 1).strip()
                .replaceFirst("(?i)\\s*!important\\s*$", "");
        if (value.isBlank() || containsDangerousCss(value)) {
            return null;
        }
        var normalized = sanitizeStyleValue(property, value);
        return normalized == null ? null : property + ": " + normalized;
    }

    private static String sanitizeStyleValue(String property, String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return switch (property) {
            case "display" -> DISPLAY_VALUES.contains(lower) ? lower : null;
            case "flex-direction" -> FLEX_DIRECTION_VALUES.contains(lower) ? lower : null;
            case "align-items", "align-self" -> ALIGN_VALUES.contains(lower) ? lower : null;
            case "justify-content" -> JUSTIFY_VALUES.contains(lower) ? lower : null;
            case "text-align" -> TEXT_ALIGN_VALUES.contains(lower) ? lower : null;
            case "overflow-x", "overflow-y" -> OVERFLOW_VALUES.contains(lower) ? lower : null;
            case "object-fit" -> Set.of("fill", "contain", "cover", "none", "scale-down")
                    .contains(lower) ? lower : null;
            case "box-sizing" -> Set.of("border-box", "content-box").contains(lower)
                    ? lower : null;
            case "flex" -> FLEX_VALUE.matcher(lower).matches() ? lower : null;
            case "aspect-ratio" -> ASPECT_RATIO.matcher(lower).matches() ? lower : null;
            case "width", "height", "min-width", "max-width", "min-height", "max-height",
                    "gap", "margin-left", "padding-left", "text-indent" ->
                    isCssDimension(lower, true) ? lower : null;
            case "margin" -> isDimensionList(lower) ? lower : null;
            case "line-height" -> lower.equals("normal") || CSS_NUMBER.matcher(lower).matches()
                    || CSS_LENGTH.matcher(lower).matches() ? lower : null;
            case "font-size" -> isCssDimension(lower, false)
                    || Set.of("xx-small", "x-small", "small", "medium", "large", "x-large",
                            "xx-large", "smaller", "larger").contains(lower) ? lower : null;
            case "color", "background-color" -> CSS_COLOR.matcher(value).matches()
                    ? value : null;
            case "font-family" -> FONT_FAMILY.matcher(value).matches() ? value : null;
            default -> null;
        };
    }

    private static boolean containsDangerousCss(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("url(") || lower.contains("expression(")
                || lower.contains("javascript:") || lower.contains("@import")
                || lower.contains("/*") || lower.contains("\\")
                || value.indexOf('{') >= 0 || value.indexOf('}') >= 0
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
    }

    private static boolean isCssDimension(String value, boolean allowAuto) {
        return CSS_LENGTH.matcher(value).matches()
                || allowAuto && Set.of("auto", "min-content", "max-content", "fit-content")
                        .contains(value);
    }

    private static boolean isDimensionList(String value) {
        var parts = value.split("\\s+");
        return parts.length >= 1 && parts.length <= 4
                && Arrays.stream(parts).allMatch(part -> isCssDimension(part, true));
    }

    private static void sanitizeClasses(Element element, Set<String> exact,
            Pattern additionalPattern) {
        var safeClasses = element.classNames().stream()
                .filter(className -> exact.contains(className)
                        || additionalPattern != null && additionalPattern.matcher(className).matches())
                .toList();
        if (safeClasses.isEmpty()) {
            element.removeAttr("class");
        } else {
            element.attr("class", String.join(" ", safeClasses));
        }
    }

    private static void sanitizeAnchor(Element element) {
        retainEnumAttribute(element, "target", Set.of("_blank", "_self"));
        if ("_blank".equals(element.attr("target"))) {
            element.attr("rel", "nofollow noopener noreferrer");
        } else if (element.hasAttr("rel")) {
            var safeRel = Arrays.stream(element.attr("rel").split("\\s+"))
                    .map(rel -> rel.toLowerCase(Locale.ROOT))
                    .filter(Set.of("nofollow", "noopener", "noreferrer", "ugc", "sponsored")::contains)
                    .distinct()
                    .toList();
            if (safeRel.isEmpty()) {
                element.removeAttr("rel");
            } else {
                element.attr("rel", String.join(" ", safeRel));
            }
        }
    }

    private static void sanitizeTaskCheckbox(Element input) {
        var label = input.parent();
        var item = label == null ? null : label.parent();
        if (label == null || !"label".equals(label.normalName())
                || item == null || !"li".equals(item.normalName())
                || !"taskItem".equals(item.attr("data-type"))
                || !"checkbox".equalsIgnoreCase(input.attr("type"))) {
            input.remove();
            return;
        }
        input.attr("type", "checkbox");
        if ("true".equals(item.attr("data-checked"))) {
            input.attr("checked", "");
        } else {
            input.removeAttr("checked");
        }
    }

    private static void sanitizeDivDataType(Element element) {
        var type = element.attr("data-type");
        if (!Set.of("gallery", "gallery-group", "detailsContent").contains(type)) {
            element.removeAttr("data-type");
            return;
        }
        if (!"gallery".equals(type)) {
            element.removeAttr("data-group-size");
            element.removeAttr("data-layout");
            element.removeAttr("data-gap");
        }
    }

    private static void sanitizeIframe(Element element) {
        // 与 Halo 富文本编辑器保持能力一致：不限定第三方域名；UGC 的安全边界放在
        // 远程 http(s) 协议、无 userInfo、无事件/srcdoc 及受控尺寸/CSS 上。
        if (!isRemoteHttpUrl(element.attr("src"))) {
            element.remove();
            return;
        }
        sanitizeDimensionAttribute(element, "width");
        sanitizeDimensionAttribute(element, "height");
        retainEnumAttribute(element, "scrolling", Set.of("yes", "no", "auto"));
        sanitizeIntegerAttribute(element, "frameborder", 0, 1);
        sanitizeIntegerAttribute(element, "framespacing", 0, 100);
    }

    private static boolean isRemoteHttpUrl(String value) {
        try {
            var normalized = value.startsWith("//") ? "https:" + value : value;
            var uri = URI.create(normalized);
            return Set.of("http", "https").contains(
                    StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT))
                    && StringUtils.isNotBlank(uri.getRawAuthority())
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void sanitizeTableCell(Element element) {
        sanitizeIntegerAttribute(element, "colspan", 1, 100);
        sanitizeIntegerAttribute(element, "rowspan", 1, 100);
        sanitizeIntegerListAttribute(element, "colwidth");
        sanitizeIntegerListAttribute(element, "data-colwidth");
        retainEnumAttribute(element, "align", Set.of("left", "right", "center"));
    }

    private static void sanitizeDimensionAttribute(Element element, String attribute) {
        if (!element.hasAttr(attribute)) {
            return;
        }
        var value = element.attr(attribute).strip().toLowerCase(Locale.ROOT);
        if (!CSS_NUMBER.matcher(value).matches() && !isCssDimension(value, true)) {
            element.removeAttr(attribute);
        } else {
            element.attr(attribute, value);
        }
    }

    private static void sanitizeIntegerListAttribute(Element element, String attribute) {
        if (element.hasAttr(attribute) && !INTEGER_LIST.matcher(element.attr(attribute)).matches()) {
            element.removeAttr(attribute);
        }
    }

    private static void sanitizeIntegerAttribute(Element element, String attribute,
            int minimum, int maximum) {
        if (!element.hasAttr(attribute)) {
            return;
        }
        try {
            var value = Integer.parseInt(element.attr(attribute));
            if (value < minimum || value > maximum) {
                element.removeAttr(attribute);
            } else {
                element.attr(attribute, Integer.toString(value));
            }
        } catch (NumberFormatException ignored) {
            element.removeAttr(attribute);
        }
    }

    private static void sanitizeDecimalAttribute(Element element, String attribute,
            double minimum, double maximum) {
        if (!element.hasAttr(attribute)) {
            return;
        }
        try {
            var value = Double.parseDouble(element.attr(attribute));
            if (!Double.isFinite(value) || value < minimum || value > maximum) {
                element.removeAttr(attribute);
            }
        } catch (NumberFormatException ignored) {
            element.removeAttr(attribute);
        }
    }

    private static void sanitizeBooleanDataAttribute(Element element, String attribute) {
        var value = element.attr(attribute).toLowerCase(Locale.ROOT);
        if (!Set.of("true", "false").contains(value)) {
            element.removeAttr(attribute);
        } else {
            element.attr(attribute, value);
        }
    }

    private static void retainEnumAttribute(Element element, String attribute,
            Set<String> allowedValues) {
        if (!element.hasAttr(attribute)) {
            return;
        }
        var value = element.attr(attribute).toLowerCase(Locale.ROOT);
        if (!allowedValues.contains(value)) {
            element.removeAttr(attribute);
        } else {
            element.attr(attribute, value);
        }
    }

    private static void retainExactAttribute(Element element, String attribute,
            Set<String> allowedValues) {
        if (element.hasAttr(attribute) && !allowedValues.contains(element.attr(attribute))) {
            element.removeAttr(attribute);
        }
    }

    /**
     * 清洗设置页 Iconify 输出的 SVG（公告/置顶徽标 / 分类图标）。
     *
     * <p>仅放行含 {@code <svg} 的片段，含 {@code <script>} 一律拒绝（返回 null，
     * 由调用方回退默认图标）；其余输入经 {@link #SVG_SAFELIST} 解析式清洗。</p>
     */
    public static String cleanSvg(String svg) {
        if (StringUtils.isBlank(svg)) {
            return null;
        }
        var s = svg.strip();
        var lower = s.toLowerCase(Locale.ROOT);
        if (!lower.contains("<svg") || lower.contains("<script")) {
            return null;
        }
        var cleaned = Jsoup.clean(s, "", SVG_SAFELIST, SVG_OUTPUT);
        return cleaned.contains("<svg") ? cleaned : null;
    }

    /**
     * 提取全文纯文本（搜索索引用，不截断）。
     *
     * @param html 原始 HTML
     * @return 纯文本（可为空串）
     */
    public static String plainText(String html) {
        return (html == null || html.isBlank()) ? "" : Jsoup.parse(html).text().strip();
    }

    /**
     * 提取纯文本并截断，用于自动摘要。
     *
     * @param html 原始 HTML
     * @param maxLength 最大长度
     * @return 纯文本摘要（可为空串）
     */
    public static String plainExcerpt(String html, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var text = Jsoup.parse(html).text().strip();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
