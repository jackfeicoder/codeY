package io.github.jackfeicoder.codey.skill;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import io.github.jackfeicoder.codey.tool.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 有界 UTF-8/frontmatter 解析；错误不回显正文，禁止 YAML 自定义类型与重复键。 */
public final class SkillParser {
    public static final int MAX_FILE_BYTES = 64 * 1024;
    public record Parsed(SkillDefinition definition, String body, List<String> warnings) { }
    public Parsed parse(Path file, String source) {
        try {
            byte[] bytes = read(file, MAX_FILE_BYTES);
            String text = decode(bytes).replace("\r\n", "\n");
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            var warnings=new ArrayList<String>();
            String folder=file.getParent().getFileName().toString();
            String name=SkillDefinition.identifier(folder);
            Map<?,?> fields=Map.of();String body=text.strip();
            if(text.startsWith("---\n")) {
                int end=text.indexOf("\n---\n",4);
                if(end<0 || end>8192)throw invalid("frontmatter 缺少结束标记，或超过8K字符限制。");
                var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);
                options.setNestingDepthLimit(8);options.setCodePointLimit(8192);
                Object loaded=new Yaml(new SafeConstructor(options)).load(text.substring(4,end));
                if(loaded!=null && !(loaded instanceof Map<?,?>))throw invalid("frontmatter 应为 YAML 字段映射。");
                if(loaded instanceof Map<?,?> mapping)fields=mapping;
                body=text.substring(end+5).strip();
            } else warnings.add("技能 "+name+" 没有frontmatter，已使用目录标识和正文摘要。");
            if(body.isBlank())throw invalid("技能正文为空。");
            Object rawName=fields.get("name"),rawDescription=fields.get("description");
            String displayName=rawName instanceof String value&&!value.isBlank()?value.strip():folder;
            String description=rawDescription instanceof String value&&!value.isBlank()?value.strip():null;
            if(description==null) {
                String firstLine=body.lines().filter(line->!line.isBlank()).findFirst().orElse(displayName).replaceFirst("^#+\\s*","").strip();
                description=SkillDefinition.preview(firstLine.isEmpty()?displayName:firstLine,512);
                warnings.add("技能 "+name+" 缺少有效description，已使用正文首行摘要。");
            }
            Set<String> supported=Set.of("name","description","disable-model-invocation","user-invocable");
            for(Object key:fields.keySet()) if (!(key instanceof String s) || !supported.contains(s)) {
                warnings.add("技能 "+name+" 含尚未支持的元数据字段；未应用其产品专属语义。");break;
            }
            boolean model=!bool(fields,"disable-model-invocation",false), user=bool(fields,"user-invocable",true);
            var definition=new SkillDefinition(name,description,source,file.toRealPath(),file.getParent().toRealPath(),model,user,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),displayName);
            return new Parsed(definition,body,List.copyOf(warnings));
        } catch (ToolException expected) { throw expected; }
        catch (CharacterCodingException invalid) {throw invalid("技能文件不是有效UTF-8文本。");}
        catch (org.yaml.snakeyaml.error.YAMLException invalid) {throw invalid("YAML格式错误：请检查缩进、重复字段、字段结构或不支持的类型标签。");}
        catch (IOException invalid) {throw invalid("技能文件不存在、不可读或被文件系统拒绝访问。");}
        catch (Exception invalid) { throw invalid("技能元数据解析失败。"); }
    }
    private static boolean bool(Map<?,?> fields,String key,boolean fallback) {
        if(!fields.containsKey(key))return fallback;
        Object value=fields.get(key); if(value instanceof Boolean b)return b;
        if(value instanceof String || value instanceof Integer) {
            switch(value.toString().strip().toLowerCase(Locale.ROOT)) {
                case "true","yes","on","1":return true;
                case "false","no","off","0":return false;
            }
        }
        throw invalid("调用开关 "+key+" 应为true/false等明确布尔值。");
    }
    /** 读取后仍检查长度，避免文件大小检查与读取之间的增长导致无界分配。 */
    static byte[] read(Path file,int limit)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw invalid("技能文件应为普通文件，不能是目录或符号链接。");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1); if(bytes.length>limit)throw invalid("技能文件超过字节上限（"+limit+"字节）。");return bytes;
        }
    }
    static String decode(byte[] bytes)throws CharacterCodingException {
        String result=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(result.indexOf('\0')>=0)throw invalid("技能文件含二进制空字节，不是纯文本。"); return result;
    }
    static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.SKILL_INVALID,message); }
}
