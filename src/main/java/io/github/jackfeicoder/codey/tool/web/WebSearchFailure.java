package io.github.jackfeicoder.codey.tool.web;

/** 静态诊断分类，不保留 HTTP 原始正文；是否切换备用由宿主判断。 */
public final class WebSearchFailure extends RuntimeException {
    public enum Kind { TIMEOUT, NETWORK, RATE_LIMIT, UNAVAILABLE, AUTH, INVALID_RESPONSE, REJECTED }
    private final Kind kind;
    private final int httpStatus;
    public WebSearchFailure(Kind kind){this(kind,0);}
    public WebSearchFailure(Kind kind,int httpStatus){super(kind.name()+(httpStatus==0?"":" (HTTP "+httpStatus+")"));this.kind=kind;this.httpStatus=httpStatus;}
    public Kind kind(){return kind;}
    public int httpStatus(){return httpStatus;}
    public boolean fallbackAllowed(){return switch(kind){case TIMEOUT,NETWORK,RATE_LIMIT,UNAVAILABLE->true;default->false;};}
}
