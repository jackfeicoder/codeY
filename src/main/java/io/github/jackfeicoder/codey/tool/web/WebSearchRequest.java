package io.github.jackfeicoder.codey.tool.web;

import java.util.*;

/** 模型侧统一参数，不含地址、凭证、超时或服务商选择。 */
public record WebSearchRequest(String query,int maxResults,List<String> allowedDomains,String timeRange) {
    public WebSearchRequest {allowedDomains=List.copyOf(allowedDomains);}
}
