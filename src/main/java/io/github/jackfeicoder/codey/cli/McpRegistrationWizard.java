package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.mcp.McpConfigStore;
import io.github.jackfeicoder.codey.mcp.McpConnectionManager;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/** 用户显式调用的 MCP 注册向导；只保存配置/刷新目录，不调用模型或启动 MCP 服务。 */
public final class McpRegistrationWizard {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 主终端共用输入，但所有向导输入均禁用历史与命令补全。 */
    private final ConsoleInput input;
    private final PrintWriter output;
    private final McpConfigStore store;
    private final McpConnectionManager manager;

    public McpRegistrationWizard(ConsoleInput input, PrintWriter output, McpConfigStore store, McpConnectionManager manager) {
        this.input=input; this.output=output; this.store=store; this.manager=manager;
    }

    /** 完成后新条目立即加入休眠目录；任一步取消均不写磁盘。 */
    public void run() throws IOException {
        output.println("MCP 注册向导：参数以服务方接入说明为准；输入 /cancel 取消，EOF/Ctrl+C 同样取消。");
        output.println("HTTP 为已运行的 MCP 端点；stdio 为本机 MCP 程序。注册后保持休眠，不测试连接。");
        try {
            String type=choice("连接方式 [1 HTTP / 2 stdio]（默认 1）> ","1",List.of("1","2","http","streamable-http","stdio"));
            String name=field("serverName（字母开头，最多32个字母/数字/_/-）> ",false,
                    s->s.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")&&!manager.names().contains(s),"名称格式不正确或已经存在，请重新填写。");
            manager.checkRegistration(name);
            String description=field("能力简介（模型据此选择服务，最多500字符）> ",false,
                    s->!s.isBlank()&&s.length()<=500&&s.chars().noneMatch(Character::isISOControl),"请填写简短且非空的一行能力说明。");
            ObjectNode entry=JSON.createObjectNode().put("description",description).put("enabled",true).put("activation","lazy");
            boolean http=!type.equals("2")&&!type.equals("stdio");
            if(http) http(entry); else stdio(entry);
            entry.put("startupTimeoutSeconds",15).put("toolTimeoutSeconds",60);
            store.validate(name,entry);
            output.println("待注册："+ConsoleApprovalHandler.clean(name)+" | "+(http?"Streamable HTTP":"stdio"));
            output.println("能力："+ConsoleApprovalHandler.clean(description));
            output.println("保存位置："+ConsoleApprovalHandler.clean(store.file().toString())+"；默认休眠，启动/调用仍按权限审批。");
            String save=choice("确认保存并注册？[Y/n]> ","y",List.of("y","yes","n","no"));
            if(save.equals("n")||save.equals("no")) throw new Cancelled();
            // 锁内重新读取文件及检查重复名，保护其他进程刚注册的条目。
            manager.checkRegistration(name);
            manager.register(store.register(name,entry));
            output.println("已注册 MCP："+name+" | DORMANT。服务目录已立即更新，无需重启；直接提出任务，Agent 会按需加载。");
        } catch(Cancelled cancelled) { output.println("已取消 MCP 注册，配置未修改。"); }
        catch(ConfigException | IllegalArgumentException failed) {
            // 只展示程序产生的通用原因，不回显用户填写的 JSON/凭证或任意底层异常。
            output.println("MCP 注册未保存：请检查名称是否重复、环境引用、现有 mcp.json 格式及目录写入权限。");
        }
        output.flush();
    }

    /** HTTP 的 URL 是 MCP 端点，认证最终统一投影为 headers；OAuth 首版未接入。 */
    private void http(ObjectNode entry) throws IOException {
        entry.put("transport","streamable-http");
        entry.put("url",field("MCP 服务地址（完整 http/https 端点）> ",false,McpRegistrationWizard::httpUrl,"请输入有效的 http/https MCP 端点，不含用户名/密码或片段。"));
        String auth=choice("认证 [1 无 / 2 Bearer Token / 3 自定义请求头或API Key]（默认1）> ","1",List.of("1","2","3"));
        if(auth.equals("1")) return;
        var headers=entry.putObject("headers");
        if(auth.equals("2")) { headers.put("Authorization","Bearer "+credential("Token（不含 Bearer 前缀）")); return; }
        output.println("按服务方说明添加请求头，例如 X-API-Key；头值填写完整内容。空名称结束。");
        for(int count=0;count<64;count++) {
            String key=field("请求头名称（回车结束）> ",false,s->s.isEmpty()||headerName(s),"请求头名称格式不正确。");
            if(key.isEmpty()) return;
            boolean duplicate=false; var names=headers.fieldNames(); while(names.hasNext()) if(names.next().equalsIgnoreCase(key)) duplicate=true;
            if(duplicate) { output.println("同名请求头已填写，请使用其他名称。"); count--; continue; }
            headers.put(key,credential("请求头值"));
        }
    }

    /** 命令和参数分开保存为 argv；不解析 shell 字符串，Windows 路径空格保持原样。 */
    private void stdio(ObjectNode entry) throws IOException {
        entry.put("transport","stdio");
        output.println("command 只填程序名或可执行文件路径，参数逐项填写；例如 java 和 -jar、E:/tools/server.jar。");
        entry.put("command",field("可执行程序 command> ",false,s->!s.isBlank()&&s.length()<=8192,"请填写可执行程序。"));
        var args=entry.putArray("args");
        for(int count=0;count<256;count++) {
            String arg=field("参数 "+(count+1)+"（回车结束，路径无需额外引号）> ",false,s->s.length()<=8192,"参数过长，请重新填写。");
            if(arg.isEmpty()) break; args.add(arg);
        }
        String cwd=field("工作目录 cwd（回车继承 CodeY 启动目录）> ",false,s->{
            if(s.isEmpty()) return true;
            try{return Files.isDirectory(store.file().getParent().resolve(Path.of(s)).normalize());}catch(RuntimeException e){return false;}
        },"工作目录不存在；相对路径以配置目录为基准。");
        if(!cwd.isEmpty()) entry.put("cwd",cwd);
        output.println("可选：给服务器传递环境变量，例如 GITHUB_TOKEN；变量名回车结束。");
        var env=entry.putObject("env");
        for(int count=0;count<64;count++) {
            String key=field("服务器环境变量名（回车结束）> ",false,s->s.isEmpty()||variableName(s),"请使用有效环境变量名。");
            if(key.isEmpty()) break;
            if(env.has(key)) {output.println("同名变量已填写，请使用其他名称。"); count--; continue;}
            env.put(key,credential("变量值"));
        }
    }

    /** 引用已有变量或隐藏输入本地明文值；不把值写入日志/模型请求/交互历史。 */
    private String credential(String label) throws IOException {
        String source=choice(label+"来源 [1 引用.env/环境变量 / 2 隐藏输入并存入本地mcp.json]（默认1）> ","1",List.of("1","2"));
        if(source.equals("1")) {
            String variable=field("来源变量名（例如 GITHUB_MCP_TOKEN）> ",false,
                    s->variableName(s)&&store.hasVariable(s),"变量未设置、为空或名称有误；可 /cancel 后设置，或选择隐藏输入方式。");
            return "${"+variable+"}";
        }
        output.println("该值保存在本地 mcp.json 中，不加密；交互终端隐藏输入，配置请勿提交版本库。");
        return field(label+"（隐藏输入）> ",true,s->!s.isBlank()&&s.length()<=8192&&s.chars().noneMatch(Character::isISOControl),"请填写非空且不含换行的值。");
    }

    private String choice(String prompt,String fallback,List<String> allowed) throws IOException {
        return field(prompt,false,s->s.isEmpty()||allowed.contains(s.toLowerCase(java.util.Locale.ROOT)),"请选择提示中的选项。")
                .transform(s->s.isEmpty()?fallback:s.toLowerCase(java.util.Locale.ROOT));
    }
    private String field(String prompt,boolean secret,Predicate<String> valid,String error) throws IOException {
        while(true) {
            String value=input.readSetup(prompt,secret);
            if(value==null) throw new Cancelled();
            value=value.strip();
            if(List.of("/cancel","/back","/exit").contains(value)) throw new Cancelled();
            if(valid.test(value)) return value;
            output.println(error);
        }
    }
    private static boolean variableName(String value) { return value.matches("[A-Za-z_][A-Za-z0-9_]*"); }
    private static boolean headerName(String value) {
        return value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                && !List.of("host","content-length","content-type","accept","mcp-session-id","mcp-protocol-version").contains(value.toLowerCase(java.util.Locale.ROOT));
    }
    private static boolean httpUrl(String value) {
        try {var uri=URI.create(value);return List.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null;}
        catch(RuntimeException invalid){return false;}
    }
    /** 仅用于正常结束向导，不传播为聊天失败。 */
    private static final class Cancelled extends RuntimeException { }
}
