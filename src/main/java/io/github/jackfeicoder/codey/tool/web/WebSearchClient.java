package io.github.jackfeicoder.codey.tool.web;

import java.time.Duration;

/** 搜索引擎适配接口；新增厂商实现此接口，不改变 Agent 工具协议。 */
public interface WebSearchClient {
    String name();
    WebSearchResponse search(WebSearchRequest request,Duration timeout) throws InterruptedException;
}
