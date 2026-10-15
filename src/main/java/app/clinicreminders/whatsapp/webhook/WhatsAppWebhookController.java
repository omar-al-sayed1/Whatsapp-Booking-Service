package app.clinicreminders.whatsapp.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import app.clinicreminders.whatsapp.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The URL registered with Meta. GET answers the verification handshake; POST verifies the
 * signature, stores the delivery and answers 200 straight away. Processing happens in
 * {@link WebhookInboxProcessor}.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WebhookSignatureVerifier signatures;
    private final WebhookInbox inbox;
    private final byte[] verifyToken;

    WhatsAppWebhookController(WebhookSignatureVerifier signatures, WebhookInbox inbox, WhatsAppProperties properties) {
        this.signatures = signatures;
        this.inbox = inbox;
        this.verifyToken = properties.verifyToken().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                  @RequestParam(name = "hub.verify_token", required = false) String token,
                                  @RequestParam(name = "hub.challenge", required = false) String challenge) {
        boolean valid = "subscribe".equals(mode) && challenge != null && token != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), verifyToken);
        if (!valid) {
            log.warn("Rejected a webhook verification request");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping
    ResponseEntity<Void> receive(@RequestBody byte[] body,
                                 @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!signatures.isValid(body, signature)) {
            log.warn("Rejected a webhook delivery with a missing or invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        inbox.store(new String(body, StandardCharsets.UTF_8));
        return ResponseEntity.ok().build();
    }
}
