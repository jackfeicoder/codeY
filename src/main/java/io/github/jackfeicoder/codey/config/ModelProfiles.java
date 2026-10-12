package io.github.jackfeicoder.codey.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 保存别名、默认选择和原始配置的不可变快照；选中某套配置时才构建 LlmConfig。
 * 流程：校验别名 → 生成字段前缀 → 加载目标配置；切换返回新快照。
 */
public final class ModelProfiles {
    /** 已声明的别名列表，统一为小写且不可修改。 */
    private final List<String> profileNames;
    /** 当前选中的配置别名，不是服务商的模型 ID。 */
    private final String activeProfile;
    /** 合并后的配置字符串，可能含密钥，因此不直接展示或打印。 */
    private final Map<String, String> values;
    /** true 使用 CODEY_PROFILE_* 前缀，false 兼容旧版 CODEY_LLM_*。 */
    private final boolean namedProfiles;

    /** 复制输入集合，并确认当前选择属于已声明的别名。 */
    ModelProfiles(List<String> profileNames, String activeProfile,
                  Map<String, String> values, boolean namedProfiles) {
        this.profileNames = List.copyOf(profileNames);
        this.values = Map.copyOf(values);
        this.namedProfiles = namedProfiles;
        this.activeProfile = requireKnownProfile(activeProfile);
    }

    /** 获取可选别名，用于 CLI 列表展示。 */
    public List<String> profileNames() {
        return profileNames;
    }

    /** 获取当前别名；不会读取或校验其模型字段。 */
    public String activeProfile() {
        return activeProfile;
    }

    /** 校验并返回当前选中配置，调用方可据此创建模型客户端。 */
    public LlmConfig activeConfig() {
        return configFor(activeProfile);
    }

    /** 读取指定别名的配置，不改变当前选择。 */
    public LlmConfig configFor(String name) {
        String knownName = requireKnownProfile(name); // 已确认存在的规范化别名。
        String prefix = namedProfiles // 决定要读取哪一组字段，避免不同配置串用凭证。
                ? "CODEY_PROFILE_" + knownName.toUpperCase(Locale.ROOT) + "_"
                : "CODEY_LLM_";
        return ConfigLoader.buildConfig(values, prefix);
    }

    /** 先验证目标配置再返回新选择；验证失败时原快照保持不变。 */
    public ModelProfiles select(String name) {
        String knownName = requireKnownProfile(name); // 新选择必须是已声明的别名。
        configFor(knownName);
        return new ModelProfiles(profileNames, knownName, values, namedProfiles);
    }

    /** 限制别名格式并转小写，确保字段前缀可以稳定生成。 */
    static String normalizeName(String name) {
        if (name == null || !name.strip().matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new ConfigException("Profile aliases must start with a letter and contain only letters, digits or underscores.");
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** 在格式校验之外检查别名是否已加入 CODEY_PROFILE_NAMES。 */
    private String requireKnownProfile(String name) {
        String normalized = normalizeName(name); // 用于大小写无关的列表匹配。
        if (!profileNames.contains(normalized)) {
            throw new ConfigException("Selected profile is not declared. Check CODEY_PROFILE_NAMES and CODEY_ACTIVE_PROFILE, or --profile.");
        }
        return normalized;
    }

    /** 只展示别名和选择，不展示包含密钥的 values。 */
    @Override
    public String toString() {
        return "ModelProfiles[profileNames=" + profileNames + ", activeProfile=" + activeProfile + "]";
    }
}
