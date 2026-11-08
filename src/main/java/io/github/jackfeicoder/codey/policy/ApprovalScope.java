package io.github.jackfeicoder.codey.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 用长度前缀区分作用域字段；只保存摘要，不将凭证或查询正文写入授权列表。 */
public final class ApprovalScope {
    private ApprovalScope() { }
    public static String key(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = java.util.Objects.requireNonNullElse(part, "").getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
