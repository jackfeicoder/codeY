package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ConfigLoader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;

/** MCP 配置的持久化入口：保留原始条目/变量引用，文件锁内校验并原子替换。 */
public final class McpConfigStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 与启动加载器共用配置目录；不随会话工作区变化。 */
    private final Path directory;
    /** 环境快照仅用于解析引用，不打印或序列化到配置。 */
    private final Map<String,String> environment;

    public McpConfigStore(Path startup, Map<String,String> environment) {
        this.directory = ConfigLoader.configurationDirectory(startup,environment).toAbsolutePath().normalize();
        this.environment = Map.copyOf(environment);
    }
    public Path file() { return directory.resolve("mcp.json"); }

    /** 只判断凭证引用是否已配置，不返回或展示实际值。 */
    public boolean hasVariable(String name) {
        String value = environment.containsKey(name) ? environment.get(name)
                : io.github.jackfeicoder.codey.config.DotEnvReader.read(directory.resolve(".env")).get(name);
        return value != null && !value.isBlank();
    }

    /** 内存预检用于向导提示；并发注册仍会在保存锁内重新验证。 */
    public McpServerConfig validate(String name, ObjectNode entry) {
        var document = JSON.createObjectNode(); document.putObject("mcpServers").set(name,entry.deepCopy());
        return McpConfigLoader.parse(directory,environment,document).get(0);
    }

    /** 不静默覆盖同名服务。验证失败、锁占用或原子替换失败时保留原文件。 */
    public McpServerConfig register(String name, ObjectNode entry) {
        ObjectNode candidate = entry.deepCopy();
        validate(name,candidate);
        return mutate(name,servers -> {
            if (servers.has(name)) throw new ConfigException("MCP server name already exists; choose another name.");
            servers.set(name,candidate);
        });
    }

    /** 用户菜单启停持久化；启用时重新解析原始变量引用，缺失凭证则保持原状态。 */
    public McpServerConfig setEnabled(String name, boolean enabled) {
        return mutate(name,servers -> {
            if (!servers.has(name)) throw new ConfigException("MCP server is missing from the configuration.");
            ((ObjectNode)servers.get(name)).put("enabled",enabled);
        });
    }

    /** 注册与启停共用锁内增量修改，不将其他服务的已解析凭证反写到磁盘。 */
    private McpServerConfig mutate(String name, java.util.function.Consumer<ObjectNode> mutation) {
        Path temp = null;
        try {
            rejectRedirectedPath(directory);
            Files.createDirectories(directory);
            Path lock = directory.resolve("mcp.json.lock");
            rejectRedirectedPath(lock);
            try (var channel = FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) throw new ConfigException("MCP configuration is busy; retry registration later.");
                var document = McpConfigLoader.readDocument(file());
                // 先验证现有文档，损坏文件不自动重建，防止覆盖其他服务。
                McpConfigLoader.parse(directory,environment,document);
                var servers = (ObjectNode)document.get("mcpServers");
                mutation.accept(servers);
                var configs = McpConfigLoader.parse(directory,environment,document);
                McpServerConfig registered = configs.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
                byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
                if (bytes.length > 262144) throw new ConfigException("MCP configuration exceeds 256 KiB.");
                temp = Files.createTempFile(directory,".mcp-register-",".tmp");
                try (var output = FileChannel.open(temp,StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                }
                // 所有写者共用锁；同目录原子移动，不降级为可能留下半个 JSON 的直接写入。
                Files.move(temp,file(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                temp = null;
                return registered;
            }
        } catch (ConfigException expected) { throw expected; }
        catch (Exception failed) { throw new ConfigException("MCP configuration was not saved; check file validity, lock and directory permissions (values hidden)."); }
        finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (java.io.IOException ignored) { }
        }
    }

    /** 拒绝符号链接祖先/锁文件，避免把配置写到意外位置。 */
    private static void rejectRedirectedPath(Path path) {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new ConfigException("MCP configuration path must not contain symbolic links.");
    }
}
