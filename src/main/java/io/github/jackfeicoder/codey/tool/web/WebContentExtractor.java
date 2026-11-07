package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.tool.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** 静态正文提取，不执行脚本或发起请求；保留标题、列表、代码块和公开链接。 */
public final class WebContentExtractor {
    public record Content(String title,List<String> lines) {public Content{lines=List.copyOf(lines);}}
    static boolean supports(String type){return Set.of("text/html","application/xhtml+xml","text/markdown","text/x-markdown","text/plain").contains(media(type));}
    private static String media(String type){return type.split(";",2)[0].strip().toLowerCase(Locale.ROOT);}
    public Content extract(byte[] bytes,String type,String url)throws InterruptedException {
        try {
            String charset=null;var match=java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").matcher(type);
            if(match.find())charset=Charset.forName(match.group(1)).name();
            String title="",text;
            if(media(type).contains("html")) {
                var doc=Jsoup.parse(new ByteArrayInputStream(bytes),charset,url);title=doc.title();
                doc.select("script,style,noscript,nav,header,footer,aside,form,iframe,svg,canvas,template,[hidden],[aria-hidden=true]").remove();
                Element root=doc.selectFirst("main");if(root==null)root=doc.selectFirst("article");if(root==null)root=doc.body();
                StringBuilder result=new StringBuilder();
                NodeTraversor.traverse(new NodeVisitor(){
                    public void head(Node node,int depth){
                        if(Thread.currentThread().isInterrupted())throw new Cancelled();
                        if(result.length()>2*1024*1024)throw FetchHttpClient.tooLarge();
                        if(node instanceof TextNode value){result.append(value.getWholeText());return;}
                        if(!(node instanceof Element element))return;
                        String tag=element.normalName();
                        if(element.isBlock() || tag.equals("br"))result.append('\n');
                        if(tag.matches("h[1-6]"))result.append("#".repeat(tag.charAt(1)-'0')).append(' ');
                        if(tag.equals("li"))result.append("- ");if(tag.equals("pre"))result.append("\n```\n");
                        if(tag.equals("a"))result.append('[');
                    }
                    public void tail(Node node,int depth){
                        if(!(node instanceof Element element))return;
                        String tag=element.normalName();
                        if(tag.equals("a")) {
                            String href=element.absUrl("href");
                            try {var uri=java.net.URI.create(href);if(uri.getHost()!=null && uri.getUserInfo()==null && Set.of("https","http").contains(uri.getScheme()) && href.length()<2048)result.append("](").append(href.replace(")","%29")).append(')');else result.append(']');}
                            catch(Exception invalid){result.append(']');}
                        }
                        if(tag.equals("pre"))result.append("\n```\n");if(element.isBlock())result.append('\n');
                    }
                },root);
                text=result.toString();
            }else {
                text=(charset==null?StandardCharsets.UTF_8:Charset.forName(charset)).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(text.length()>2*1024*1024)throw FetchHttpClient.tooLarge();
            // 固定把超长物理行折为最多1000码点，分页不会卡在一条超长行上。
            var lines=new ArrayList<String>();int blanks=0;
            for(String line:text.replace("\r\n","\n").replace('\r','\n').split("\n",-1)) {
                line=line.replaceAll("[\\p{Cc}&&[^\\t]]","").stripTrailing();
                if(line.isBlank()){if(blanks++>0)continue;}else blanks=0;
                while(line.codePointCount(0,line.length())>1000){int end=line.offsetByCodePoints(0,1000);lines.add(line.substring(0,end));line=line.substring(end);}
                lines.add(line);
            }
            if(lines.stream().allMatch(String::isBlank))throw FetchHttpClient.error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"No readable static text; page may require JavaScript, login or a browser.");
            return new Content(SearchResults.shorten(title,300),lines);
        }catch(Cancelled cancelled){Thread.currentThread().interrupt();throw new InterruptedException();}
        catch(ToolException|InterruptedException expected){throw expected;}
        catch(Exception invalid){throw FetchHttpClient.error(ToolResult.ErrorCode.INVALID_ENCODING,"Page charset or text structure could not be decoded.");}
    }
    private static final class Cancelled extends RuntimeException { }
}
