package io.github.jackfeicoder.codey.mcp.oauth;

import java.net.URI;

/** 主机提供浏览器/终端交互；认证模块不读取主 Agent 的批准文本或暴露令牌。 */
public interface OAuthInteraction {
    boolean available();
    void openBrowser(URI authorizationUrl,String scope);
    /** 无动态注册的服务可能需要预注册公共客户端 ID；null 表示用户取消。 */
    default String clientId(URI issuer,URI callback) { return null; }
    static OAuthInteraction unavailable() {
        return new OAuthInteraction() {
            public boolean available(){return false;}
            public void openBrowser(URI url,String scope){throw OAuthHttp.required("请在交互终端继续任务，完成 OAuth 授权。");}
        };
    }
}
