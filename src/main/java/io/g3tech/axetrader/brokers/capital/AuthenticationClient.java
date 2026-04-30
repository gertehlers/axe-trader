package io.g3tech.axetrader.brokers.capital;

import io.g3tech.axetrader.brokers.capital.domain.CapitalUserConfig;
import io.g3tech.axetrader.brokers.capital.dto.session.CreateSessionRequest;
import io.g3tech.axetrader.brokers.capital.dto.session.CreateSessionResponse;
import io.g3tech.axetrader.brokers.capital.dto.session.GetEncryptionKeySessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static io.g3tech.axetrader.brokers.capital.Constants.X_SECURITY_TOKEN;
import static io.g3tech.axetrader.brokers.capital.Constants.API_V1_SESSION;
import static io.g3tech.axetrader.brokers.capital.Constants.API_V1_SESSION_ENCRYPTION_KEY;
import static io.g3tech.axetrader.brokers.capital.Constants.CLIENT_SSO_TOKEN;
import static io.g3tech.axetrader.brokers.capital.Constants.X_CAP_API_KEY;

@Service
public class AuthenticationClient {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationClient.class);

    private final RestClient restClient;
    private final CapitalUserConfig capitalUserConfig;

    public AuthenticationClient(@Value("${brokers.capital.api.url}") String baseUrl, CapitalUserConfig capitalUserConfig) {
        this.restClient = RestClient.builder().baseUrl(URI.create(baseUrl)).build();
        this.capitalUserConfig = capitalUserConfig;
    }

    public ConversationContext createSession() {
        logger.debug("Creating session...");
        var encryptionKeySessionResponse = restClient.get()
                .uri(API_V1_SESSION_ENCRYPTION_KEY.value())
                .headers(this::appendHttpHeaders)
                .retrieve()
                .body(GetEncryptionKeySessionResponse.class);

        Objects.requireNonNull(encryptionKeySessionResponse, "Capital session encryption key response was empty");

        var encryptedPassword = encryptPassword(encryptionKeySessionResponse.encryptionKey(), encryptionKeySessionResponse.timeStamp(), capitalUserConfig.password());
        var createSessionRequest = new CreateSessionRequest(capitalUserConfig.login(), encryptedPassword, true);

        logger.debug("Sending create session request for configured Capital.com user");
        var createSessionResponse = restClient.post()
                .uri(API_V1_SESSION.value())
                .headers(this::appendHttpHeaders)
                .body(createSessionRequest)
                .retrieve()
                .toEntity(CreateSessionResponse.class);

        var responseHeaders = createSessionResponse.getHeaders();
        var responseBody = createSessionResponse.getBody();

        Objects.requireNonNull(responseBody, "Capital create session response was empty");
        logger.debug("Session created for account {}", responseBody.currentAccountId());

        return new ConversationContext(
                responseHeaders.getFirst(CLIENT_SSO_TOKEN.value()),
                responseHeaders.getFirst(X_SECURITY_TOKEN.value()),
                responseBody.streamingHost()
        );
    }

    private void appendHttpHeaders(HttpHeaders httpHeaders) {
        httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.set(X_CAP_API_KEY.value(), capitalUserConfig.apiKey());
    }

    private String encryptPassword(String encryptionKey, Long timestamp, String password) {
        try {
            var input = "%s|%s".formatted(password, timestamp).getBytes(StandardCharsets.UTF_8);
            var encodedInput = Base64.getEncoder().encode(input);

            var keyFactory = KeyFactory.getInstance("RSA");
            var publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encryptionKey.getBytes(StandardCharsets.UTF_8))));

            var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);

            var output = cipher.doFinal(encodedInput);

            output = Base64.getEncoder().encode(output);
            return new String(output, StandardCharsets.UTF_8);
        } catch (NoSuchPaddingException | IllegalBlockSizeException | NoSuchAlgorithmException | InvalidKeySpecException | BadPaddingException | InvalidKeyException e) {
            logger.error("Failed to encrypt password", e);
            throw new RuntimeException(e);
        }
    }
}
