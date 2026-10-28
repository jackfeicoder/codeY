package io.github.jackfeicoder.codey.memory;

import java.util.regex.Pattern;

/** 用户原话的轻量意图门控；只是决定是否请求提取模型，不把匹配词直接写成记忆。 */
public final class MemoryIntentDetector {
    public enum Kind { NONE, REMEMBER, PREFERENCE, CORRECTION }
    public record Intent(Kind kind, String text, MemoryScope scopeHint) {
        public boolean triggered() { return kind != Kind.NONE; }
    }
    private static final Pattern NEGATIVE = Pattern.compile("不要.{0,6}(记住|记录|保存|记忆)|别.{0,4}(记住|记录|保存)|不用.{0,4}(记|保存)|无需.{0,4}(记|保存)|不需要.{0,4}(记|保存)");
    private static final Pattern QUESTION = Pattern.compile("(能不能|能否|是否|会不会|如何|怎么|你能|可以).{0,14}(记住|记忆|保存|记录)|(记住|长期记忆).{0,16}(吗|什么意思|是什么|如何|怎么)");
    private static final Pattern REMEMBER = Pattern.compile("记住|记一下|记下来|加入长期记忆|保存到.{0,6}记忆");
    private static final Pattern PREFERENCE = Pattern.compile("(以后|今后|往后|从现在起).{0,24}(都|一律|默认|始终|统一)|默认.{0,24}(回答|回复|使用|用|解释|称呼)|(以后|今后|每次).{0,24}(回答|回复|解释|讲).{0,24}(中文|英文|详细|简短|示例|面试|说明)|每次.{0,20}都");
    private static final Pattern CORRECTION = Pattern.compile("(之前|以前|刚才|上次).{0,24}(说错|不对|错了|纠正)|纠正一下|更正一下");
    private static final Pattern PROJECT = Pattern.compile("(这个|当前|本|此)(项目|仓库)|项目级|只.{0,4}(项目|仓库)");
    private static final Pattern USER = Pattern.compile("所有项目|全局|用户级|个人偏好|我习惯|我喜欢|(以后|今后|每次|默认).{0,24}(回答|回复|解释|讲|称呼|中文|英文)");

    /** 先去代码、引用与否定/能力问句，保守降低误触发；不检查assistant或工具结果。 */
    public Intent detect(String input) {
        if (input == null || input.isBlank() || input.strip().startsWith("/") || input.length() > 16000) return none();
        String text = input.replaceAll("(?s)```.*?```|~~~.*?~~~", " ")
                .replaceAll("(?s)```.*$|~~~.*$", " ")
                .replaceAll("(?m)^\\s*>.*$", " ")
                .replaceAll("`[^`]*`", " ").strip();
        // 引用中的触发词不参与门控；外部已有明确请求时保留引号里的值，例如“中文”。
        String source = text;
        text = text.replaceAll("“[^”]*”|「[^」]*」|\"[^\"]*\"|'[^']*'", " ").strip();
        if (text.isBlank() || NEGATIVE.matcher(text).find() || QUESTION.matcher(text).find()
                || text.matches("(?s)^(我|我已经|已经|终于)记住了?.*")) return none();
        Kind kind = CORRECTION.matcher(text).find() ? Kind.CORRECTION
                : REMEMBER.matcher(text).find() ? Kind.REMEMBER : PREFERENCE.matcher(text).find() ? Kind.PREFERENCE : Kind.NONE;
        MemoryScope scope = PROJECT.matcher(text).find() ? MemoryScope.PROJECT : USER.matcher(text).find() ? MemoryScope.USER : null;
        return kind == Kind.NONE ? none() : new Intent(kind, source, scope);
    }
    private static Intent none() { return new Intent(Kind.NONE, "", null); }
}
