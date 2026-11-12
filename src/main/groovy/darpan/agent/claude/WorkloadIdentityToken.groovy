package darpan.agent.claude

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetWebIdentityTokenRequest

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * DAR-BE-244. Keeps an AWS-signed JWT (sts:GetWebIdentityToken, audience https://api.anthropic.com) in the file named by
 * ANTHROPIC_IDENTITY_TOKEN_FILE. The Anthropic SDK reads that file each time it exchanges it for an access token
 * (FileIdentityTokenProvider reads it on every call), so no Anthropic secret exists anywhere. Minted with the task role's
 * credentials through the AWS SDK's default chain (the ECS container endpoint on prod; region from AWS_REGION).
 *
 * A token lives DURATION_SECONDS and is replaced once the file is REFRESH_AFTER_MS old, so the SDK always reads one with
 * at least 45 minutes left. The file is owner-only and replaced atomically. The token is never logged, and no exception
 * message carries it.
 */
class WorkloadIdentityToken {
    static final String AUDIENCE = "https://api.anthropic.com"
    static final String SIGNING_ALGORITHM = "RS256"
    static final int DURATION_SECONDS = 3600
    static final long REFRESH_AFTER_MS = 15 * 60 * 1000L

    private static final Object LOCK = new Object()
    private static StsClient sts

    /** Mints one token; tests replace it (and {@link #reset} restores it). */
    static Closure<String> minter = { -> mintWithSts() }

    /** Mints a new token into the file unless it already holds one younger than REFRESH_AFTER_MS. */
    static void ensureFresh(String path) {
        Path file = Paths.get(path).toAbsolutePath()
        synchronized (LOCK) {
            if (fresh(file)) return
            String jwt
            try {
                jwt = minter.call()?.trim()
            } catch (Exception e) {
                // AWS errors name the role or the refusal, never a token.
                throw new IllegalStateException("AWS identity token for Anthropic could not be minted: " + e.getClass().simpleName + ": " + e.message, e)
            }
            if (!jwt) throw new IllegalStateException("AWS returned no identity token for Anthropic")
            write(file, jwt)
        }
    }

    static boolean fresh(Path file) {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) return false
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
        return age >= 0 && age < REFRESH_AFTER_MS
    }

    static void write(Path file, String jwt) {
        Path dir = file.parent
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        Path tmp = Files.createTempFile(dir, ".identity-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.write(tmp, jwt.getBytes(StandardCharsets.UTF_8))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    static String mintWithSts() {
        StsClient client
        synchronized (LOCK) {
            if (sts == null) sts = StsClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
            client = sts
        }
        return client.getWebIdentityToken(GetWebIdentityTokenRequest.builder()
                .audience(AUDIENCE)
                .signingAlgorithm(SIGNING_ALGORITHM)
                .durationSeconds(DURATION_SECONDS)
                .build()).webIdentityToken()
    }

    /** Tests: restore the real minter and drop the STS client. */
    static void reset() {
        synchronized (LOCK) {
            minter = { -> mintWithSts() }
            StsClient old = sts
            sts = null
            if (old != null) {
                try { old.close() } catch (Exception ignored) { }
            }
        }
    }
}
