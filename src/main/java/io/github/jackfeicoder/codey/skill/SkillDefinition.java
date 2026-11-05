package io.github.jackfeicoder.codey.skill;

import java.nio.file.Path;

/** 发现阶段的技能元数据；指纹用于检测发现后、激活前的文件变更。 */
public record SkillDefinition(String name, String description, String source, Path file, Path directory,
                              boolean modelInvocable, boolean userInvocable, String fingerprint) {
    public static boolean validName(String name) { return name != null && name.length() <= 64 && name.matches("[a-z0-9]+(?:-[a-z0-9]+)*"); }
}
