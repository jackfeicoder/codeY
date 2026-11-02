package io.github.jackfeicoder.codey.mcp.oauth;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** 按认证身份隔离的凭证缓存：Windows CurrentUser DPAPI 加密，锁内刷新/轮换及原子替换。 */
public final class OAuthTokenStore {
    /** 不含密码；access/refresh token 仅写入加密载荷，不写入 mcp.json 或日志。 */
    public record Credentials(String resource,String issuer,String clientId,String authorizationEndpoint,String tokenEndpoint,
                              String accessToken,String refreshToken,long obtainedAt,Long expiresAt,String scope) {
        public Credentials {
            if(resource==null||issuer==null||clientId==null||clientId.isBlank()||authorizationEndpoint==null||tokenEndpoint==null
                    ||accessToken==null||accessToken.isBlank()||accessToken.length()>16384||accessToken.chars().anyMatch(Character::isISOControl)
                    ||(refreshToken!=null&&(refreshToken.length()>16384||refreshToken.chars().anyMatch(Character::isISOControl))))throw new IllegalArgumentException();
        }
        public boolean fresh(long now){return expiresAt!=null&&now+Math.min(30,Math.max(0,(expiresAt-obtainedAt)/10))<expiresAt;}
        @Override public String toString(){return "OAuthCredentials[redacted]";}
    }
    /** 可注入仅用于测试的加密器；生产没有明文回退。 */
    public interface Protector {byte[] encrypt(byte[] clear);byte[] decrypt(byte[] encrypted);}
    private final Path directory;
    private final Protector protector;
    public OAuthTokenStore(Path dataDirectory) {
        this(dataDirectory,new Protector(){
            public byte[] encrypt(byte[] clear){if(!Platform.isWindows())throw OAuthHttp.failed("此平台暂未接入系统凭证库；OAuth 持久化需要 Windows DPAPI。");return Crypt32Util.cryptProtectData(clear);}
            public byte[] decrypt(byte[] encrypted){if(!Platform.isWindows())throw OAuthHttp.failed("此平台暂未接入系统凭证库。");return Crypt32Util.cryptUnprotectData(encrypted);}
        });
    }
    public OAuthTokenStore(Path dataDirectory,Protector protector){this.directory=dataDirectory.toAbsolutePath().normalize().resolve("mcp/oauth");this.protector=protector;}
    /** 读取不创建目录；损坏或其他用户加密的文件明确报错，不默默覆盖。 */
    public Credentials load(String key) {
        try{return read(file(key));}catch(Exception e){throw OAuthHttp.failed("OAuth 凭证读取或解密失败，请检查当前系统用户及数据目录。");}
    }
    public Credentials save(String key,Credentials credentials){return update(key,old->credentials);}
    /** 网络刷新可在锁内进行，避免多个进程同时消耗同一可轮换 refresh_token；不在锁内等待浏览器。 */
    public Credentials update(String key,UnaryOperator<Credentials> operation) {
        Path temp=null;
        try {
            Path file=file(key);checkPath(directory);Files.createDirectories(directory);checkPath(file.resolveSibling(key+".lock"));
            try(var channel=FileChannel.open(file.resolveSibling(key+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                var lock=channel.tryLock()) {
                if(lock==null)throw OAuthHttp.failed("OAuth 凭证正在更新，请稍后重试。");
                Credentials old=read(file),next=Objects.requireNonNull(operation.apply(old));
                if(next==old)return next;
                byte[] clear=OAuthHttp.JSON.writeValueAsBytes(next),encrypted;
                try{encrypted=protector.encrypt(clear);}finally{Arrays.fill(clear,(byte)0);}
                if(encrypted.length>131072)throw new IllegalArgumentException();
                temp=Files.createTempFile(directory,".oauth-",".tmp");
                try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(encrypted);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
                Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temp=null;return next;
            }
        }catch(io.github.jackfeicoder.codey.tool.ToolException e){throw e;}
        catch(Exception e){throw OAuthHttp.failed("OAuth 凭证保存失败，原记录保留；请检查数据目录、加密和文件锁。");}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    /** 用户菜单重新授权：仅删除本服务本地凭证，不删除注册配置，也不声称远端令牌已撤销。 */
    public void delete(String key) {
        Path file=file(key);
        try {
            checkPath(file);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            Path lockFile=directory.resolve(key+".lock");checkPath(lockFile);
            try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.tryLock()) {
                if(lock==null)throw OAuthHttp.failed("OAuth 凭证正在更新，暂未清除。");
                Files.deleteIfExists(file);
            }
        }catch(Exception e){throw OAuthHttp.failed("本地 OAuth 凭证清除失败，请检查文件锁及权限。");}
    }

    private Credentials read(Path file)throws Exception {
        checkPath(file);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file)||Files.size(file)>131072)throw new IllegalArgumentException();
        byte[] encrypted;
        try(var in=Files.newInputStream(file)){encrypted=in.readNBytes(131073);}
        if(encrypted.length>131072)throw new IllegalArgumentException();
        byte[] clear=protector.decrypt(encrypted);
        try{if(clear.length>131072)throw new IllegalArgumentException();return OAuthHttp.JSON.readValue(clear,Credentials.class);}
        finally{Arrays.fill(clear,(byte)0);}
    }
    private Path file(String key){if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();return directory.resolve(key+".bin");}
    private static void checkPath(Path path){for(var p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException();}
}
