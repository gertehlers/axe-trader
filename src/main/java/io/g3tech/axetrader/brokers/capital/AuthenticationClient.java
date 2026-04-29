package io.g3tech.axetrader.brokers.capital;

import io.g3tech.axetrader.brokers.capital.dto.session.CreateSessionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Service
public class AuthenticationClient {

    Logger logger = LoggerFactory.getLogger(AuthenticationClient.class);

    public ConversationContext createSession(CreateSessionRequest createSessionRequest, @Value("${brokers.capital.api.key}") String apiKey) {
        var conversationContext = new ConversationContext(apiKey, null, null, null);


        return null;
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
