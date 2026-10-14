package io.github.jackfeicoder.codey.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 保存供应商别名、模型列表与选择的不可变快照；选中时构建单个模型的 LlmConfig。
 * 流程：解析别名/ID → 生成字段前缀 → 校验 ID 属于列表 → 合并公共配置；切换返回新快照。
 */
public final class ModelProfiles {
    /**
     * 列表的非敏感信息，不包含 URL、密钥或完整配置。
     * @param alias /model 使用的配置别名
     * @param modelId 提供商的模型 ID，缺失时显示占位说明
     * @param configured 本地配置校验是否通过，不表示远端一定在线
     */
    public record ProfileInfo(String alias, String modelId, boolean configured) { }
    /** 已声明的别名列表，统一为小写且不可修改。 */
    private final List<String> profileNames;
    /** 当前选中的供应商配置别名，与 activeModel 分开保存。 */
    private final String activeProfile;
    /** 明确选择的模型 ID；null 表示使用该 Profile 的第一个模型。 */
    private final String activeModel;
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
        ProfileSelection selected = ProfileSelection.parse(activeProfile);
        this.activeProfile = requireKnownProfile(selected.alias());
        this.activeModel = selected.modelId();
    }

    /** 获取可选别名，用于 CLI 列表展示。 */
    public List<String> profileNames() {
        return profileNames;
    }

    /** 列出全部已声明配置及模型 ID；坏配置标记而不阻止查看其他模型，不创建客户端。 */
    public List<ProfileInfo> listInfo() {
        List<ProfileInfo> result = new ArrayList<>();
        for (String name : profileNames) {
            List<String> ids;
            try { ids = modelIdsFor(name); }
            catch (ConfigException invalid) {
                result.add(new ProfileInfo(name, "模型列表待完善", false));
                continue;
            }
            for (String id : ids) {
                boolean configured;
                try { configFor(name + " " + id); configured = true; }
                catch (ConfigException invalid) { configured = false; }
                result.add(new ProfileInfo(name, id, configured));
            }
        }
        return List.copyOf(result);
    }

    /** 获取当前别名；不会读取或校验其模型字段。 */
    public String activeProfile() {
        return activeProfile;
    }

    /** 校验并返回当前选中配置，调用方可据此创建模型客户端。 */
    public LlmConfig activeConfig() {
        return configFor(new ProfileSelection(activeProfile, activeModel).value());
    }

    /** 返回完整选择值，用于会话和默认偏好；明确记录实际模型而非仅供应商。 */
    public String activeSelection() {
        return activeProfile + " " + activeConfig().model();
    }

    /** 读取“别名 [模型ID]”对应的单模型配置，不改变当前选择。 */
    public LlmConfig configFor(String name) {
        ProfileSelection selection = ProfileSelection.parse(name);
        String knownName = requireKnownProfile(selection.alias());
        List<String> ids = modelIdsFor(knownName);
        String selectedId = selection.modelId() == null ? ids.get(0) : selection.modelId();
        if (!ids.contains(selectedId)) {
            throw new ConfigException("Selected model ID is not declared in this Profile's MODEL list. Use /list model.");
        }
        // 公共地址与密钥保持不变，仅把选定的单个模型传给原有 LlmConfig 构建流程。
        Map<String, String> selectedValues = new HashMap<>(values);
        String prefix = prefixFor(knownName);
        selectedValues.put(prefix + "MODEL", selectedId);
        return ConfigLoader.buildConfig(selectedValues, prefix);
    }

    /** 解析逗号分隔的模型 ID，保持配置顺序；空项、重复项明确报错。 */
    public List<String> modelIdsFor(String alias) {
        String key = prefixFor(requireKnownProfile(alias)) + "MODEL";
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) throw new ConfigException(key + " is required.");
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            String id;
            try { id = ProfileSelection.requireModelId(part); }
            catch (ConfigException invalid) { throw new ConfigException(key + " contains an invalid model ID."); }
            if (ids.contains(id)) throw new ConfigException(key + " contains duplicate model IDs.");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** Profile 别名只用于生成配置字段前缀，与模型 ID 分开处理。 */
    private String prefixFor(String alias) {
        return namedProfiles ? "CODEY_PROFILE_" + alias.toUpperCase(Locale.ROOT) + "_" : "CODEY_LLM_";
    }

    /** 先验证目标配置再返回新选择；验证失败时原快照保持不变。 */
    public ModelProfiles select(String name) {
        ProfileSelection selection = ProfileSelection.parse(name);
        configFor(selection.value());
        return new ModelProfiles(profileNames, selection.value(), values, namedProfiles);
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
