package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.*;

/** 技能用户目录及独立上下文额度；还必须服从当前模型的总输入预算。 */
public record SkillSettings(Path userDirectory, int catalogTokens, int instructionTokens, int resourceTokens) {
    public SkillSettings {
        userDirectory = Objects.requireNonNull(userDirectory).toAbsolutePath().normalize();
        if (catalogTokens < 256 || catalogTokens > 8000 || instructionTokens < 256 || instructionTokens > 32000
                || resourceTokens < 256 || resourceTokens > 32000) throw new ConfigException("Skill token budgets are outside supported ranges.");
    }
    /** 默认使用共享的 ~/.agents/skills；自定义相对目录以配置目录为基准。 */
    public static SkillSettings load(Path startup, Map<String,String> environment) {
        Path config = ConfigLoader.configurationDirectory(startup, environment);
        var values = new HashMap<>(DotEnvReader.read(config.resolve(".env"))); values.putAll(environment);
        try {
            String directory = values.getOrDefault("CODEY_SKILLS_DIR", Path.of(System.getProperty("user.home"), ".agents", "skills").toString());
            if (directory.isBlank()) throw new IllegalArgumentException();
            return new SkillSettings(config.resolve(directory), number(values,"CODEY_SKILL_CATALOG_TOKENS",2048),
                    number(values,"CODEY_SKILL_INSTRUCTION_TOKENS",8192), number(values,"CODEY_SKILL_RESOURCE_TOKENS",8192));
        } catch (IllegalArgumentException invalid) { throw new ConfigException("Invalid CODEY_SKILL_* limits or CODEY_SKILLS_DIR."); }
    }
    private static int number(Map<String,String> values,String key,int fallback) { return Integer.parseInt(values.getOrDefault(key,""+fallback).strip()); }
}
