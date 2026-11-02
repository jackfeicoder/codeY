package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.mcp.oauth.OAuthInteraction;
import java.net.URI;
import java.io.PrintWriter;

/** OAuth 只显示授权链接/状态；登录在系统浏览器完成，输入不进入模型或聊天历史。 */
public final class ConsoleOAuthInteraction implements OAuthInteraction {
    private final ConsoleInput input;private final PrintWriter output;private final boolean piped;
    public ConsoleOAuthInteraction(ConsoleInput input,PrintWriter output,boolean piped){this.input=input;this.output=output;this.piped=piped;}
    public boolean available(){return input.isInteractive()||System.console()!=null||piped;}
    public void openBrowser(URI uri,String scope) {
        output.println("[MCP OAuth] 请在浏览器登录并确认授权。授权站点："+uri.getHost());
        output.println("权限范围："+(scope.isEmpty()?"由服务方授权页显示":ConsoleApprovalHandler.clean(scope)));
        output.println("若浏览器未打开，可复制此临时授权链接（不包含访问令牌）：");output.println(uri);output.flush();
        try{if(java.awt.Desktop.isDesktopSupported()&&java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))java.awt.Desktop.getDesktop().browse(uri);}
        catch(Exception ignored){ /* 仍可通过上方链接完成授权。 */ }
    }
    public String clientId(URI issuer,URI callback) {
        output.println("[MCP OAuth] 服务未提供动态客户端注册。请在 "+issuer.getHost()+" 登记公共客户端，回调地址为："+callback);
        output.flush();
        try {String value=input.readSetup("client_id（回车取消）> ",false);return value==null||value.isBlank()||value.startsWith("/")?null:value.strip();}
        catch(java.io.IOException cancelled){return null;}
    }
}
