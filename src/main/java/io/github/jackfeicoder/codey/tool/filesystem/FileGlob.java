package io.github.jackfeicoder.codey.tool.filesystem;

/** 跨平台文件名/路径通配符：*、? 匹配单个路径段，独立的 ** 匹配零层或多层目录。 */
public final class FileGlob {
    /** 限制模式长度，匹配使用动态规划，不把模型输入拼接为正则表达式。 */
    public static final int MAX_PATTERN_LENGTH = 256;
    /** 规范化后的模式段；没有 / 的模式只比较文件名。 */
    private final String[] segments;
    /** 明确控制大小写行为，不依赖 Windows/Linux 的默认文件系统规则。 */
    private final boolean caseSensitive;

    /** 构造并校验相对模式；Windows 分隔符统一为 /，其他字符均按字面匹配。 */
    public FileGlob(String pattern, boolean caseSensitive) {
        if (pattern == null || pattern.isBlank() || pattern.codePointCount(0, pattern.length()) > MAX_PATTERN_LENGTH
                || pattern.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("pattern must be a nonblank glob of at most 256 characters.");
        }
        String normalized = pattern.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) {
            throw new IllegalArgumentException("pattern must be relative to the search directory.");
        }
        segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || (segment.contains("**") && !segment.equals("**"))) {
                throw new IllegalArgumentException("Use nonempty path segments; ** must occupy a complete segment.");
            }
        }
        this.caseSensitive = caseSensitive;
    }

    /** 输入是相对搜索目录的 / 分隔路径；单文件名模式递归匹配任意层的文件名。 */
    public boolean matches(String relativePath) {
        String[] path = relativePath.split("/");
        if (segments.length == 1 && !segments[0].equals("**")) {
            return segmentMatches(segments[0], path[path.length - 1]);
        }
        boolean[] previous = new boolean[path.length + 1];
        previous[0] = true;
        for (int segmentIndex = 0; segmentIndex < segments.length; segmentIndex++) {
            String segment = segments[segmentIndex];
            boolean[] current = new boolean[path.length + 1];
            if (segment.equals("**")) {
                // 末尾双星必须消耗最终文件名，避免 src/** 错误命中一个名为 src 的普通文件。
                boolean trailing = segmentIndex == segments.length - 1;
                current[0] = !trailing && previous[0];
                for (int i = 1; i <= path.length; i++) {
                    current[i] = (trailing ? previous[i - 1] : previous[i]) || current[i - 1];
                }
            } else {
                for (int i = 1; i <= path.length; i++) {
                    current[i] = previous[i - 1] && segmentMatches(segment, path[i - 1]);
                }
            }
            previous = current;
        }
        return previous[path.length];
    }

    /** 按 Unicode 码点匹配，? 可以匹配一个中文字符或 emoji；* 不跨越目录边界。 */
    private boolean segmentMatches(String glob, String name) {
        int[] pattern = glob.codePoints().toArray();
        int[] text = name.codePoints().toArray();
        boolean[] previous = new boolean[text.length + 1];
        previous[0] = true;
        for (int point : pattern) {
            boolean[] current = new boolean[text.length + 1];
            current[0] = point == '*' && previous[0];
            for (int i = 1; i <= text.length; i++) {
                current[i] = point == '*' ? previous[i] || current[i - 1]
                        : previous[i - 1] && (point == '?' || equal(point, text[i - 1]));
            }
            previous = current;
        }
        return previous[text.length];
    }

    /** 与区域设置无关的大小写折叠，匹配过程不修改实际返回路径。 */
    private boolean equal(int left, int right) {
        return left == right || (!caseSensitive && Character.toLowerCase(Character.toUpperCase(left))
                == Character.toLowerCase(Character.toUpperCase(right)));
    }
}
