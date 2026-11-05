package io.github.jackfeicoder.codey.skill;

import java.nio.file.Path;

/** name 是目录派生的调用标识，displayName 是作者名称；完整描述不受目录展示长度限制。 */
public record SkillDefinition(String name, String description, String source, Path file, Path directory,
                              boolean modelInvocable, boolean userInvocable, String fingerprint, String displayName) {
    public SkillDefinition(String name,String description,String source,Path file,Path directory,boolean modelInvocable,boolean userInvocable,String fingerprint) {
        this(name,description,source,file,directory,modelInvocable,userInvocable,fingerprint,name);
    }
    public static boolean validName(String name) { return name != null && name.length() <= 128 && name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*"); }
    /** 有空格/特殊符号的目录获得稳定别名，哈希避免规范化后的重名；常见目录名保持原样。 */
    public static String identifier(String directoryName) {
        if(validName(directoryName))return directoryName;
        String base=singleLine(directoryName).replaceAll("[^\\p{L}\\p{N}._-]+","-").replaceAll("^[^\\p{L}\\p{N}]+","");
        if(base.isEmpty())base="skill";
        base=preview(base,48).replace("…","");
        try {
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(directoryName.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12);
            return base+"-"+hash;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** 仅用于目录/终端展示；不改写存储的多行描述或技能文件。 */
    public static String singleLine(String value) {return value.replaceAll("[\\s\\p{Z}\\p{Cntrl}]+"," ").strip();}
    public static String preview(String value,int maxPoints) {
        String text=singleLine(value);
        return text.codePointCount(0,text.length())<=maxPoints?text:text.substring(0,text.offsetByCodePoints(0,maxPoints))+"…";
    }
}
