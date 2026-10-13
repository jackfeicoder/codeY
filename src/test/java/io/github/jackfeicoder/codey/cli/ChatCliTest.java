package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 通过内存输入和替代客户端验证交互行为，不调用厂商服务或读取真实密钥。 */
class ChatCliTest {
    /** 连续两轮发送前文，/exit 后的输入不再处理。 */
    @Test
    void secondTurnContainsFirstQuestionAndAnswer() throws IOException {
        List<LlmRequest> requests = new ArrayList<>(); // 捕获实际传给客户端的请求。
        Harness harness = new Harness(request -> {
            requests.add(request);
            return reply("回答" + requests.size());
        });
        harness.run("项目叫 CodeY\n项目叫什么\n/exit\n不应发送\n");
        assertEquals(2, requests.size());
        assertEquals(List.of(Message.system("base"), Message.user("项目叫 CodeY"),
                Message.assistant("回答1"), Message.user("项目叫什么")), requests.get(1).messages());
        assertEquals(5, harness.session.messages().size());
        assertTrue(harness.output.toString().contains("回答2"));
        assertTrue(harness.output.toString().contains("Bye."));
    }

    /** 空行和未知命令不发送；清空后的下一轮只带系统指令和当前问题。 */
    @Test
    void clearPreservesSystemAndCommandsNeverReachModel() throws IOException {
        List<LlmRequest> requests = new ArrayList<>();
        Harness harness = new Harness(request -> {
            requests.add(request);
            return reply("answer");
        });
        harness.run(" \n/model other\nfirst\n /clear \nsecond\n");
        assertEquals(2, requests.size());
        assertEquals(List.of(Message.system("base"), Message.user("second")), requests.get(1).messages());
        assertTrue(harness.output.toString().contains("Unknown command"));
        assertTrue(harness.output.toString().contains("Conversation cleared"));
    }

    /** 普通调用失败后继续输入，成功历史保留，失败问题不出现在下一次请求。 */
    @Test
    void failedTurnDoesNotPolluteHistory() throws IOException {
        List<LlmRequest> requests = new ArrayList<>();
        Harness harness = new Harness(request -> {
            requests.add(request);
            if (requests.size() == 2) {
                throw new LlmException(LlmException.Kind.HTTP, 500, "fixture failure");
            }
            return reply("answer");
        });
        harness.run("first\nfailed\nthird\n/exit\n");
        assertEquals(3, requests.size());
        assertEquals(List.of(Message.system("base"), Message.user("first"),
                Message.assistant("answer"), Message.user("third")), requests.get(2).messages());
        assertEquals(5, harness.session.messages().size());
        assertTrue(harness.errors.toString().contains("This turn was not saved"));
    }

    /** 未注册工具也回填错误，随后最终回答和完整消息组一并提交。 */
    @Test
    void unknownToolIsReturnedToModelAndCompletedGroupIsCommitted() throws IOException {
        List<LlmRequest> requests = new ArrayList<>();
        Harness harness = new Harness(request -> {
            requests.add(request);
            if (requests.size() == 1) {
                return new LlmResponse(Message.assistantToolCalls(
                        List.of(new ToolCall("call-1", "read_file", "{}"))), "tool_calls", null);
            }
            return reply("answer");
        });
        harness.run("tool question\nnext\n");
        assertEquals(3, requests.size()); // 首问两次模型调用，第二个用户问题一次。
        assertEquals("tool question", requests.get(1).messages().get(1).content());
        assertEquals("call-1", requests.get(1).messages().get(3).toolCallId());
        assertTrue(requests.get(1).messages().get(3).content().contains("UNKNOWN_TOOL"));
        assertEquals("next", requests.get(2).messages().get(5).content());
        assertEquals(7, harness.session.messages().size());
        assertEquals("", harness.errors.toString());
    }

    /** 长度截断时保存已收到的回答并提示，下一轮可根据该文本要求继续。 */
    @Test
    void truncatedAnswerIsSavedWithWarning() throws IOException {
        Harness harness = new Harness(request -> new LlmResponse(Message.assistant("partial"), "length", null));
        harness.run("question\n");
        assertEquals(Message.assistant("partial"), harness.session.messages().get(2));
        assertTrue(harness.errors.toString().contains("finish_reason=length"));
    }

    /** 预算退出展示明确状态，不保存残缺任务，下一用户输入仍可正常完成。 */
    @Test
    void budgetExhaustionIsReportedAndNextInputUsesOnlyCommittedHistory() throws IOException {
        int[] count = {0};
        Harness harness = new Harness(request -> {
            if (++count[0] <= 8) return new LlmResponse(Message.assistantToolCalls(
                    List.of(new ToolCall("call-" + count[0], "unknown", "{}"))), "tool_calls", null);
            assertEquals(List.of(Message.system("base"), Message.user("next")), request.messages());
            return reply("done");
        });
        harness.run("loop\nnext\n/exit\n");
        assertEquals(9, count[0]);
        assertEquals(3, harness.session.messages().size());
        assertTrue(harness.errors.toString().contains("BUDGET_EXHAUSTED"));
    }

    /** EOF 正常结束且不发送空请求。 */
    @Test
    void eofEndsWithoutModelCall() throws IOException {
        Harness harness = new Harness(request -> {
            fail("EOF should not call the model");
            return reply("unused");
        });
        harness.run("");
        assertEquals(1, harness.session.messages().size());
        assertTrue(harness.output.toString().contains("Bye."));
    }

    /** 请求中断时结束当前交互，后续输入不再提交。 */
    @Test
    void interruptedRequestEndsConversation() throws IOException {
        List<LlmRequest> requests = new ArrayList<>();
        Harness harness = new Harness(request -> {
            requests.add(request);
            throw new LlmException(LlmException.Kind.INTERRUPTED, "fixture interruption");
        });
        harness.run("first\nsecond\n");
        assertEquals(1, requests.size());
        assertEquals(1, harness.session.messages().size());
    }

    /** 生成无工具的普通回答，供不同交互场景复用。 */
    private static LlmResponse reply(String text) {
        return new LlmResponse(Message.assistant(text), "stop", null);
    }

    /** 每个用例独立持有会话和输入输出，替代真实终端。 */
    private static final class Harness {
        /** 本用例的会话历史。 */
        private final Session session = new Session("base");
        /** 捕获正常输出。 */
        private final StringWriter output = new StringWriter();
        /** 捕获错误输出。 */
        private final StringWriter errors = new StringWriter();
        /** 用例提供的模型行为。 */
        private final LlmClient client;

        /** 绑定测试客户端，其余组件由本用例创建。 */
        private Harness(LlmClient client) {
            this.client = client;
        }

        /** 将多行输入交给真实交互循环处理。 */
        private void run(String text) throws IOException {
            new ChatCli(client, session, new BufferedReader(new StringReader(text)),
                    new PrintWriter(output), new PrintWriter(errors)).run();
        }
    }
}
