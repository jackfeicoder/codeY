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
            if (!text.startsWith("---\n")) throw invalid("SKILL.md must begin with YAML frontmatter.");
            int end = text.indexOf("\n---\n",4);
            if (end < 0 || end > 8192) throw invalid("Skill frontmatter is missing its closing marker or exceeds 8K characters.");
            var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(8); options.setCodePointLimit(8192);
            Object loaded = new Yaml(new SafeConstructor(options)).load(text.substring(4,end));
            if (!(loaded instanceof Map<?,?> fields)) throw invalid("Skill frontmatter must be a mapping.");
            Object rawName=fields.get("name"), rawDescription=fields.get("description");
            if (!(rawName instanceof String name) || !SkillDefinition.validName(name)
                    || !name.equals(file.getParent().getFileName().toString())) throw invalid("Skill name must match its kebab-case directory name.");
            if (!(rawDescription instanceof String description) || description.isBlank() || description.length()>512
                    || description.codePoints().anyMatch(Character::isISOControl)) throw invalid("Skill description must be one nonempty line, at most 512 characters.");
            String body=text.substring(end+5).strip();
            if(body.isBlank()) throw invalid("Skill instructions must not be empty.");
            var warnings=new ArrayList<String>();
            Set<String> supported=Set.of("name","description","disable-model-invocation","user-invocable");
            for(Object key:fields.keySet()) if (!(key instanceof String s) || !supported.contains(s)) {
                warnings.add("技能 "+name+" 含尚未支持的元数据字段；未应用其产品专属语义。");break;
            }
            boolean model=!bool(fields,"disable-model-invocation",false), user=bool(fields,"user-invocable",true);
            var definition=new SkillDefinition(name,description.strip(),source,file.toRealPath(),file.getParent().toRealPath(),model,user,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return new Parsed(definition,body,List.copyOf(warnings));
        } catch (ToolException expected) { throw expected; }
        catch (Exception invalid) { throw invalid("Skill file is unreadable, invalid UTF-8, or invalid YAML."); }
    }
    private static boolean bool(Map<?,?> fields,String key,boolean fallback) {
        if(!fields.containsKey(key))return fallback;
        Object value=fields.get(key); if(value instanceof Boolean b)return b;
        throw invalid("Skill invocation fields must be YAML booleans.");
    }
    /** 读取后仍检查长度，避免文件大小检查与读取之间的增长导致无界分配。 */
    static byte[] read(Path file,int limit)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw invalid("Skill resource must be a regular file, not a symbolic link.");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1); if(bytes.length>limit)throw invalid("Skill file exceeds its byte limit.");return bytes;
        }
    }
    static String decode(byte[] bytes)throws CharacterCodingException {
        String result=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(result.indexOf('\0')>=0)throw invalid("Skill resource is not plain text."); return result;
    }
    static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.SKILL_INVALID,message); }
}
