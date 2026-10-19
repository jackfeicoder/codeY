package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.policy.ApprovalHandler;
import io.github.jackfeicoder.codey.policy.ApprovalRequest;
import java.io.IOException;
import java.io.PrintWriter;

/** 审批输入与聊天共用 ConsoleInput；仅明确 y/yes 批准，其他输入一律拒绝。 */
public final class ConsoleApprovalHandler implements ApprovalHandler {
    private final ConsoleInput input;
    private final PrintWriter output;
    private final boolean allowPiped;
    public ConsoleApprovalHandler(ConsoleInput input, PrintWriter output, boolean allowPiped) {
        this.input = input; this.output = output; this.allowPiped = allowPiped;
    }
    @Override public Answer approve(ApprovalRequest request) throws InterruptedException {
        if (!available()) return Answer.UNAVAILABLE;
        output.println("\n待批准：" + clean(request.tool()) + " · " + clean(request.displayTarget()));
        output.println("操作：" + clean(request.operation()) + "；字节 " + request.beforeBytes() + " → " + request.afterBytes());
        output.println(request.fileGrant()
                ? "批准范围：当前会话对此文件的写入、编辑、删除（按已接入工具执行）；不包含其他文件、命令或联网。"
                : "批准范围：仅当前这一次操作，后续操作仍需按模式检查。");
        for (String line : request.preview().split("\\R", -1)) output.println(clean(line));
        return confirm("[y] 批准 / [n] 拒绝（默认）> ");
    }
    /** full 的切换是用户显式操作，不从模型回复读取，也不复用文件授权。 */
    public boolean confirmFull() throws InterruptedException {
        if (!available()) return false;
        output.println("full 将跳过常规审批；当前工具仍只支持工作区文件，参数/路径/冲突检查及 PLAN 只读约束继续生效。");
        return confirm("启用 full？[y/N]> ") == Answer.APPROVE;
    }
    private boolean available() { return input.isInteractive() || allowPiped; }
    private Answer confirm(String prompt) throws InterruptedException {
        output.flush();
        try {
            String answer = input.readApproval(prompt);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (answer == null) return Answer.UNAVAILABLE;
            return answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes") ? Answer.APPROVE : Answer.DENY;
        } catch (IOException failed) { return Answer.UNAVAILABLE; }
    }
    /** ANSI、控制字符与 Unicode 行分隔都不作为终端命令输出。 */
    public static String clean(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().limit(1200).forEach(cp -> {
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || Character.getType(cp) == Character.FORMAT) {
                result.append(String.format("\\u%04x", cp));
            } else result.appendCodePoint(cp);
        });
        return result.toString();
    }
}
