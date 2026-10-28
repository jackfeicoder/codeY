package io.github.jackfeicoder.codey.memory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 门控只看用户自然语言，不把引用、问句和否定当成保存授权。 */
class MemoryIntentDetectorTest {
    private final MemoryIntentDetector detector = new MemoryIntentDetector();
    @ParameterizedTest @ValueSource(strings = {"请记住我喜欢先看调用流程", "帮我记一下这个项目的缓存决策", "以后都用中文回答", "今后解释源码时附带面试示例", "默认使用中文回答", "每次解释都附带示例", "之前说错了，项目暂时保留旧缓存", "更正一下：我喜欢简短解释"})
    void persistentSignalsTriggerASecondStageNotDirectSaving(String text) { assertTrue(detector.detect(text).triggered(), text); }
    @ParameterizedTest @ValueSource(strings = {"你好", "分析一下登录功能", "测试一下", "用中文回答这个问题", "你能记住吗？", "是否可以保存到长期记忆？", "如何保存记忆", "记住是什么意思？", "不要记住这些内容", "不用记录这个临时路径", "别保存这次信息", "不需要长期记忆", "我记住了，谢谢", "/remember --user 内容", "“以后都用中文回答”这句话是什么意思", "`请记住密码`这个字符串", "> 请记住以后都使用中文", "```java\n// 请记住以后都用中文\n```", "~~~\n请记住这件事\n~~~", "```未闭合代码块：请记住这件事"})
    void routineNegatedQuestionAndQuotedInputsSkip(String text) { assertFalse(detector.detect(text).triggered(), text); }
    @Test void scopeHintsAndReferenceRemovalAreConservative() {
        assertEquals(MemoryScope.USER, detector.detect("以后都用中文回答").scopeHint());
        assertEquals(MemoryScope.PROJECT, detector.detect("请记住这个项目保留旧缓存方案").scopeHint());
        assertEquals(MemoryIntentDetector.Kind.CORRECTION, detector.detect("之前说错了，以后都简短回答").kind());
        var result = detector.detect("请记住我喜欢简短回答\n```\nSECRET\n```");
        assertTrue(result.triggered()); assertFalse(result.text().contains("SECRET"));
    }
    @Test void quotedValueIsPreservedWhenOutsideTextAlreadyExpressesIntent() {
        var intent = detector.detect("以后都用“中文”回答");
        assertTrue(intent.triggered()); assertTrue(intent.text().contains("“中文”"));
    }
}
