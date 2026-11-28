package com.atifah.common.security.jwt;

import com.atifah.common.core.constant.JwtConstants;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.common.security.config.JwtUsage;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 签发与校验测试：typ、jti 与 RS256。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class JwtTokenProviderTest {

    private static final String SECRET = "atifah-test-secret-change-me-32bytes";

    @TempDir
    Path tempDir;

    @Test
    void hs256TokenCarriesAccessTypeAndJti() {
        JwtTokenProvider provider = provider(hs256());
        Claims claims = provider.parseToken(provider.generateToken(42L, "alice"));

        assertEquals("42", claims.getSubject());
        assertEquals("alice", claims.get("username"));
        assertEquals(JwtConstants.TOKEN_TYPE_ACCESS, claims.get("typ"));
        assertNotNull(claims.getId());
    }

    @Test
    void hs256VerifierCannotGenerateToken() {
        JwtProperties properties = hs256();
        properties.setUsage(JwtUsage.VERIFIER);
        JwtTokenProvider verifier = provider(properties);

        assertThrows(IllegalStateException.class, () -> verifier.generateToken(42L, "alice"));
    }

    @Test
    void hs256SignerCannotParseToken() {
        JwtProperties properties = hs256();
        properties.setUsage(JwtUsage.SIGNER);
        JwtTokenProvider signer = provider(properties);
        String token = signer.generateToken(42L, "alice");

        assertThrows(IllegalStateException.class, () -> signer.parseToken(token));
    }

    @Test
    void refreshTokenIsRejectedAsAccessToken() {
        JwtTokenProvider provider = provider(hs256());
        String refreshToken = Jwts.builder()
                .issuer("atifah")
                .subject("42")
                .id("refresh-jti")
                .claim(JwtConstants.CLAIM_TYPE, JwtConstants.TOKEN_TYPE_REFRESH)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> provider.parseToken(refreshToken));
    }

    @Test
    void tokenWithoutTypeIsRejected() {
        JwtTokenProvider provider = provider(hs256());
        String token = Jwts.builder()
                .issuer("atifah")
                .subject("42")
                .id("legacy-jti")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> provider.parseToken(token));
    }

    @Test
    void rs256RoundTripAndGatewayVerifiesWithPublicKeyOnly() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        JwtProperties issuerProperties = rs256(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
        JwtTokenProvider issuer = provider(issuerProperties);
        String token = issuer.generateToken(7L, "ops");

        JwtProperties verifierProperties = new JwtProperties();
        verifierProperties.setAlgorithm("RS256");
        verifierProperties.setUsage(JwtUsage.VERIFIER);
        verifierProperties.setPublicKeyLocation(issuerProperties.getPublicKeyLocation());
        JwtTokenProvider verifier = provider(verifierProperties);

        assertEquals("7", verifier.parseToken(token).getSubject());
        assertThrows(IllegalStateException.class, () -> verifier.generateToken(7L, "ops"));
    }

    @Test
    void rs256SigningOnlyProviderDoesNotRequirePublicKey() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("RS256");
        properties.setUsage(JwtUsage.SIGNER);
        properties.setPrivateKeyLocation(writePem(
                "private-signing-only.pem", pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())));

        JwtTokenProvider signer = provider(properties);
        String token = signer.generateToken(9L, "issuer");

        Claims claims = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("9", claims.getSubject());
        assertThrows(IllegalStateException.class, () -> signer.parseToken(token));
    }

    @Test
    void unsupportedAlgorithmFailsFast() {
        JwtProperties properties = hs256();
        properties.setAlgorithm("RS512");
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(properties));
    }

    @Test
    void hs256WithoutSecretFailsFast() {
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(properties));
    }

    @Test
    void rs256WithoutAnyKeyFailsFast() {
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("RS256");
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(properties));
    }

    @Test
    void rs256VerifierRejectsPrivateKey() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("RS256");
        properties.setUsage(JwtUsage.VERIFIER);
        properties.setPrivateKeyLocation(writePem(
                "verifier-private.pem", pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())));
        properties.setPublicKeyLocation(writePem(
                "verifier-public.pem", pem("PUBLIC KEY", keyPair.getPublic().getEncoded())));

        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(properties));
    }

    private JwtTokenProvider provider(JwtProperties properties) {
        return assertDoesNotThrow(() -> new JwtTokenProvider(properties));
    }

    private JwtProperties hs256() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("atifah");
        return properties;
    }

    private JwtProperties rs256(String privatePem, String publicPem) throws IOException {
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("RS256");
        properties.setPrivateKeyLocation(writePem("private.pem", privatePem));
        properties.setPublicKeyLocation(writePem("public.pem", publicPem));
        properties.setIssuer("atifah");
        return properties;
    }

    private String writePem(String fileName, String pem) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        return file.toUri().toString();
    }

    private static KeyPair rsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] encoded) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
