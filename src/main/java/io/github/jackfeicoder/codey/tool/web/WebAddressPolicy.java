package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.tool.*;
import java.net.*;
import java.util.*;

/** URL语法校验与连接时DNS验证分开；返回的已校验地址直接用于连接，不二次解析。 */
public final class WebAddressPolicy {
    @FunctionalInterface interface Resolver {InetAddress[] resolve(String host)throws UnknownHostException;}
    private final Resolver resolver;
    /** 仅包内本地HTTP测试使用，不提供配置或模型开关。 */
    private final boolean localFixtures;
    public WebAddressPolicy(){this(InetAddress::getAllByName,false);}
    WebAddressPolicy(Resolver resolver,boolean localFixtures){this.resolver=resolver;this.localFixtures=localFixtures;}

    public URI normalize(String value) {
        try {
            if(value==null || value.isBlank() || value.length()>2048 || value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            URI uri=URI.create(value);String host=uri.getHost();
            if(host==null || uri.getRawUserInfo()!=null || !Set.of("http","https").contains(uri.getScheme()))throw new IllegalArgumentException();
            String lower=host.toLowerCase(Locale.ROOT);
            if(!localFixtures && (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                    || lower.endsWith(".internal") || !lower.contains(".")))throw blocked();
            // 已知认证参数直接拒绝，避免把签名链接/令牌持久化到工具结果。
            String query=uri.getRawQuery();
            if(query!=null && URLDecoder.decode(query,java.nio.charset.StandardCharsets.UTF_8).matches("(?is).*(?:^|[&;])(?:api[_-]?key|access_token|token|password|signature|x-amz-signature)=.*"))throw blocked();
            if(!localFixtures && uri.getPort()!=-1 && !(uri.getScheme().equals("http") && uri.getPort()==80) && !(uri.getScheme().equals("https") && uri.getPort()==443))throw blocked();
            // 不对数值地址或特殊IPv6表示做宽松主机名解释。
            if(!localFixtures && (lower.matches("[0-9.]+") || lower.contains(":")))throw blocked();
            String normalized=uri.toASCIIString();int fragment=normalized.indexOf('#');if(fragment>=0)normalized=normalized.substring(0,fragment);
            if(!localFixtures && uri.getScheme().equals("http"))normalized="https://"+host+normalized.substring(7+uri.getRawAuthority().length());
            return URI.create(normalized).normalize();
        }catch(ToolException expected){throw expected;}
        catch(Exception invalid){throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS,"Use a public HTTP(S) URL without credentials, signed tokens or nonstandard ports.");}
    }
    InetAddress[] resolve(String host)throws UnknownHostException {
        if(Thread.currentThread().isInterrupted())throw new UnknownHostException("Fetch cancelled.");
        var addresses=resolver.resolve(host);
        if(addresses==null || addresses.length==0)throw new UnknownHostException("No usable public address.");
        for(var address:addresses)if(!(localFixtures && address.isLoopbackAddress()) && !publicAddress(address))throw blocked();
        if(Thread.currentThread().isInterrupted())throw new UnknownHostException("Fetch cancelled.");
        return addresses;
    }
    /** IPv6仅接受全球单播2000::/3，排除映射/转换/隧道等特殊地址。IPv4排除保留、内网和组播。 */
    static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())return false;
        byte[] b=address.getAddress();int a=b[0]&255,c=b[1]&255;
        if(b.length==16)return (a&0xe0)==0x20 && !(a==0x20 && c==1 && ((b[2]&255)<2 || (b[2]&255)==0x0d && (b[3]&255)==0xb8)) && !(a==0x20 && c==2) && !(a==0x3f && (c==0xfe || c==0xff));
        return a!=0 && a!=10 && a!=127 && a<224 && !(a==100 && c>=64 && c<=127) && !(a==169 && c==254)
                && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==0 || c==168)) && !(a==198 && (c==18 || c==19 || c==51 && (b[2]&255)==100))
                && !(a==192 && c==88 && (b[2]&255)==99) && !(a==203 && c==0 && (b[2]&255)==113);
    }
    static ToolException blocked(){return new ToolException(ToolResult.ErrorCode.WEB_FETCH_BLOCKED,"Web fetch requires a public address without embedded authentication; local/private/reserved targets are blocked.");}
}
