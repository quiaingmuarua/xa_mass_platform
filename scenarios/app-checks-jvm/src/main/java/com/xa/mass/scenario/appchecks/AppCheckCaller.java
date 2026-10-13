package com.xa.mass.scenario.appchecks;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Client-declared development context, never an authenticated principal. */
record AppCheckCaller(String userId, String role) {
    AppCheckCaller {
        if (userId == null) throw new AppCheckAccessException(400, "缺少开发调用方");
        userId = userId.strip();
        if (userId.isEmpty() || userId.length() > 128
                || !("admin".equals(role) || "operator".equals(role)))
            throw new AppCheckAccessException(400, "开发调用方无效");
    }

    static AppCheckCaller fromHeaders(String encodedUser, String role) {
        if (encodedUser == null || role == null)
            throw new AppCheckAccessException(400, "缺少开发调用方请求头");
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            String user = decoder.decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encodedUser))).toString();
            return new AppCheckCaller(user, role);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new AppCheckAccessException(400, "开发调用方编码无效");
        }
    }

    boolean canRead(String creator) { return "admin".equals(role) || userId.equals(creator); }
    void requireOwner(String creator) {
        if (!canRead(creator)) throw new AppCheckAccessException(403, "当前开发身份不能访问此任务");
    }
    void requireAdmin() {
        if (!"admin".equals(role)) throw new AppCheckAccessException(403, "当前操作仅对管理员开发身份开放");
    }
}
