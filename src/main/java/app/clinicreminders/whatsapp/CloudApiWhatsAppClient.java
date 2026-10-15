package app.clinicreminders.whatsapp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends through Meta's Cloud API: {@code POST /{version}/{phone-number-id}/messages} with the
 * clinic's bearer token. Request and response shapes follow Meta's "Send messages", "Templates
 * overview", "Interactive reply buttons" and "Interactive list messages" references; errors follow
 * the Cloud API error codes page. Subscribing an account follows "Get started as a Solution Partner"
 * (step 3, {@code POST /{version}/{waba-id}/subscribed_apps}).
 * Never logs the access token or message content.
 */
public class CloudApiWhatsAppClient implements WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(CloudApiWhatsAppClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final String graphVersion;

    public CloudApiWhatsAppClient(RestClient restClient, String graphVersion) {
        this.restClient = restClient;
        this.graphVersion = graphVersion;
    }

    @Override
    public String send(WhatsAppAccount account, OutboundMessage message) {
        if (account.accessToken() == null) {
            throw new WhatsAppException(null, "The clinic has no WhatsApp access token", false);
        }
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/{version}/{phoneNumberId}/messages", graphVersion, account.phoneNumberId())
                    .headers(headers -> headers.setBearerAuth(account.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(message))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, errorResponse) -> {
                        throw errorFrom(errorResponse.getStatusCode(), errorResponse.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new WhatsAppException(null, "Could not reach the WhatsApp Cloud API: " + e.getMessage(), true);
        }
        String id = response == null ? null : text(response.path("messages").path(0).path("id"));
        if (id == null) {
            throw new WhatsAppException(null, "The Cloud API response had no message id", false);
        }
        return id;
    }

    @Override
    public void subscribeApp(String businessAccountId, String accessToken) {
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/{version}/{wabaId}/subscribed_apps", graphVersion, businessAccountId)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, errorResponse) -> {
                        throw errorFrom(errorResponse.getStatusCode(), errorResponse.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new WhatsAppException(null, "Could not reach the WhatsApp Cloud API: " + e.getMessage(), true);
        }
        if (response == null || !(response.path("success").isBoolean() && response.path("success").booleanValue())) {
            throw new WhatsAppException(null, "Meta did not confirm the subscription", false);
        }
    }

    static Map<String, Object> requestBody(OutboundMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", message.to());
        switch (message) {
            case TemplateMessage template -> {
                body.put("type", "template");
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("name", template.name());
                content.put("language", Map.of("code", template.languageCode()));
                if (!template.bodyParameters().isEmpty()) {
                    List<Map<String, String>> parameters = template.bodyParameters().stream()
                            .map(value -> Map.of("type", "text", "text", value))
                            .toList();
                    content.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
                }
                body.put("template", content);
            }
            case TextMessage text -> {
                body.put("type", "text");
                body.put("text", Map.of("body", text.body()));
            }
            case ListMessage list -> {
                body.put("type", "interactive");
                List<Map<String, Object>> rows = list.rows().stream()
                        .<Map<String, Object>>map(row -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", row.id());
                            entry.put("title", row.title());
                            if (row.description() != null) {
                                entry.put("description", row.description());
                            }
                            return entry;
                        })
                        .toList();
                body.put("interactive", Map.of(
                        "type", "list",
                        "body", Map.of("text", list.body()),
                        "action", Map.of(
                                "button", list.button(),
                                "sections", List.of(Map.of("title", list.sectionTitle(), "rows", rows)))));
            }
            case ReplyButtonsMessage buttons -> {
                body.put("type", "interactive");
                List<Map<String, Object>> replyButtons = buttons.buttons().stream()
                        .<Map<String, Object>>map(button -> Map.of(
                                "type", "reply",
                                "reply", Map.of("id", button.id(), "title", button.title())))
                        .toList();
                body.put("interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", buttons.body()),
                        "action", Map.of("buttons", replyButtons)));
            }
        }
        return body;
    }

    static WhatsAppException errorFrom(HttpStatusCode status, byte[] responseBody) {
        Integer code = null;
        String title = null;
        try {
            JsonNode error = JSON.readTree(responseBody).path("error");
            code = error.path("code").isInt() ? error.path("code").intValue() : null;
            title = text(error.path("message"));
            log.warn("Cloud API rejected a request: HTTP {}, code {}, details: {}",
                    status.value(), code, text(error.path("error_data").path("details")));
        } catch (JacksonException e) {
            log.warn("Cloud API returned HTTP {} with an unreadable body", status.value());
        }
        if (title == null) {
            title = "HTTP " + status.value();
        }
        if (status.is5xxServerError()) {
            return new WhatsAppException(code, title, true);
        }
        return code == null ? new WhatsAppException(null, title, false) : WhatsAppException.fromMetaError(code, title);
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }
}
