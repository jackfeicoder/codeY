package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.mcp.McpConfigStore;
import io.github.jackfeicoder.codey.mcp.McpConnectionManager;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/** MCP 用户管理页；只展示目录/启停，连接、调用与释放交给 Agent 和宿主生命周期。 */
public final class McpMenu {
    private final ConsoleInput input;
    private final PrintWriter output;
    private final McpConnectionManager manager;
    /** 可为空：嵌入宿主仅提供管理器时仍可查看详情，配置修改需显式存储。 */
    private final McpConfigStore store;

    public McpMenu(ConsoleInput input,PrintWriter output,McpConnectionManager manager,McpConfigStore store) {
        this.input=input; this.output=output; this.manager=manager; this.store=store;
    }

    /** 菜单和辅助输入均不进入模型消息或编辑器历史，查看不隐式连接服务器。 */
    public void run() throws IOException {
        while(true) {
            List<String> names=manager.names();
            output.println("MCP 服务（注册信息长期保留，需要时由 Agent 自动加载）");
            if(names.isEmpty()) output.println("暂无已注册服务。");
            for(int i=0;i<names.size();i++) {
                String name=names.get(i);
                output.println((i+1)+". "+name+" | "+manager.state(name)+" | "+clean(manager.config(name).description()));
            }
            output.println("输入序号或服务名查看详情；r 注册；回车或 0 返回。");
            String choice=input.readSetup("MCP> ",false);
            if(back(choice)) return;
            choice=choice.strip();
            if(choice.equalsIgnoreCase("r")) { register(); continue; }
            String name=names.contains(choice)?choice:byNumber(names,choice);
            if(name==null) { output.println("请选择已列出的服务，或回车返回。"); continue; }
            if(!details(name)) return;
        }
    }
    private boolean details(String name) throws IOException {
        while(true) {
            var config=manager.config(name);
            output.println("服务："+name+" | "+manager.state(name)+" | "+config.transport());
            output.println("能力："+clean(config.description()));
            output.println("认证方式："+config.auth().mode()+"（不展示凭证）");
            output.println("当前工具："+clean(manager.tools(name)));
            output.println("自动连接："+config.permissions().autoApproveConnect()+"；可信查询工具："+clean(config.permissions().readOnlyTools().toString()));
            output.println("t 切换连接信任；r 设置可信查询工具（精确名称，逗号分隔；空行清空）");
            output.println("休眠表示已注册、尚未连接；任务中按需加载，任务结束释放，配置继续保留。");
            boolean disabled=manager.state(name).equals("DISABLED");
            output.println("e "+(disabled?"启用（恢复为休眠）":"停用（保留注册信息）")+"；回车或 b 返回列表。");
            boolean oauth=config.transport().equals("streamable-http")&&java.util.Set.of("auto","oauth").contains(config.auth().mode());
            if(oauth)output.println("a 清除本地 OAuth 授权（下次任务重新登录；不撤销远端令牌）");
            String action=input.readSetup("服务> ",false);
            if(action==null) return false;
            action=action.strip();
            if(action.isEmpty()||action.equalsIgnoreCase("b")||back(action)) return true;
            if (action.equalsIgnoreCase("t") || action.equalsIgnoreCase("r")) {
                if (store == null) { output.println("当前宿主仅支持查看。"); continue; }
                var reads = config.permissions().readOnlyTools();
                boolean connect = config.permissions().autoApproveConnect();
                if (action.equalsIgnoreCase("t")) {
                    String answer = input.readSetup("信任该服务的本地程序/端点，并"+(connect?"取消":"允许")+"自动连接？[y/N]> ",false);
                    if (answer == null) return false;
                    if (!answer.equalsIgnoreCase("y") && !answer.equalsIgnoreCase("yes")) continue;
                    connect = !connect;
                } else {
                    output.println("仅添加你已确认的查询工具；远端 readOnlyHint 和工具名不保证无副作用。写入、发邮件、删除等继续审批。");
                    String value = input.readSetup("可信查询工具（原始名称，逗号分隔，/cancel取消）> ",false);
                    if (value == null) return false;
                    if (value.strip().equals("/cancel")) continue;
                    reads = new java.util.LinkedHashSet<>();
                    for (String tool : value.split(",")) if (!tool.isBlank()) reads.add(tool.strip());
                }
                try {
                    manager.applyConfiguration(store.setPermissions(name,new io.github.jackfeicoder.codey.mcp.McpPermissions(connect,reads)));
                    output.println("MCP 信任范围已保存；已释放旧连接，下次按新配置加载。");
                } catch (ConfigException | IllegalArgumentException invalid) { output.println("信任范围未保存，请检查工具名及配置。"); }
                continue;
            }
            if(oauth&&action.equalsIgnoreCase("a")) {
                try{output.println(manager.resetAuthentication(name)?"已清除本地 OAuth 凭证；注册信息保留，下次需要时重新授权。":"当前宿主未提供 OAuth 凭证管理。");}
                catch(io.github.jackfeicoder.codey.tool.ToolException failed){output.println("OAuth 凭证清除失败，原记录保留。");}
                continue;
            }
            if(!action.equalsIgnoreCase("e")) { output.println("请选择菜单中的操作或回车返回。"); continue; }
            if(store==null) { output.println("当前宿主未提供 MCP 配置存储，仅支持查看。"); continue; }
            try {
                manager.applyConfiguration(store.setEnabled(name,disabled));
                output.println("已保存："+name+" | "+manager.state(name)+"；注册信息保留。");
            } catch(ConfigException | IllegalArgumentException failed) {
                output.println("启停未保存：请检查配置文件、环境变量及写入权限；当前状态保留。");
            }
        }
    }
    public void register() throws IOException {
        if(store==null) { output.println("当前宿主未配置 MCP 注册存储。"); return; }
        new McpRegistrationWizard(input,output,store,manager).run();
    }
    private static boolean back(String value) {
        if(value==null) return true;
        return List.of("","0","/back","/cancel","/exit").contains(value.strip());
    }
    private static String byNumber(List<String> names,String value) {
        try {int index=Integer.parseInt(value)-1;return index>=0&&index<names.size()?names.get(index):null;}
        catch(NumberFormatException invalid){return null;}
    }
    private static String clean(String text) { return ConsoleApprovalHandler.clean(text); }
}
