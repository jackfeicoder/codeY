package io.github.jackfeicoder.codey.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable configuration snapshot; only the requested profile is materialized and validated. */
public final class ModelProfiles {
    private final List<String> profileNames;
    private final String activeProfile;
    private final Map<String, String> values;
    private final boolean namedProfiles;

    ModelProfiles(List<String> profileNames, String activeProfile,
                  Map<String, String> values, boolean namedProfiles) {
        this.profileNames = List.copyOf(profileNames);
        this.values = Map.copyOf(values);
        this.namedProfiles = namedProfiles;
        this.activeProfile = requireKnownProfile(activeProfile);
    }

    public List<String> profileNames() {
        return profileNames;
    }

    public String activeProfile() {
        return activeProfile;
    }

    public LlmConfig activeConfig() {
        return configFor(activeProfile);
    }

    public LlmConfig configFor(String name) {
        String knownName = requireKnownProfile(name);
        String prefix = namedProfiles
                ? "CODEY_PROFILE_" + knownName.toUpperCase(Locale.ROOT) + "_"
                : "CODEY_LLM_";
        return ConfigLoader.buildConfig(values, prefix);
    }

    /** Validate the target before returning a new selection; the original snapshot is unchanged. */
    public ModelProfiles select(String name) {
        String knownName = requireKnownProfile(name);
        configFor(knownName);
        return new ModelProfiles(profileNames, knownName, values, namedProfiles);
    }

    static String normalizeName(String name) {
        if (name == null || !name.strip().matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new ConfigException("Profile aliases must start with a letter and contain only letters, digits or underscores.");
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private String requireKnownProfile(String name) {
        String normalized = normalizeName(name);
        if (!profileNames.contains(normalized)) {
            throw new ConfigException("Selected profile is not declared. Check CODEY_PROFILE_NAMES and CODEY_ACTIVE_PROFILE, or --profile.");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "ModelProfiles[profileNames=" + profileNames + ", activeProfile=" + activeProfile + "]";
    }
}
