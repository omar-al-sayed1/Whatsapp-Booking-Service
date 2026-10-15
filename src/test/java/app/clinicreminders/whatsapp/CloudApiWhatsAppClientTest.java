package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;

import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Request and response shapes from Meta's "Send messages" and "Templates overview" pages. */
class CloudApiWhatsAppClientTest {

    private static final String URL = "https://graph.example/v26.0/106540352242922/messages";
    private static final WhatsAppAccount ACCOUNT = new WhatsAppAccount("106540352242922", "EAAG-token");
    private static final String SUCCESS = """
            {"messaging_product":"whatsapp",
             "contacts":[{"input":"+96170123456","wa_id":"96170123456"}],
             "messages":[{"id":"wamid.SENT-1"}]}""";

    private MockRestServiceServer server;
    private CloudApiWhatsAppClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://graph.example");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CloudApiWhatsAppClient(builder.build(), "v26.0");
    }

    @Test
    void sendsATemplateWithPositionalBodyParameters() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer EAAG-token"))
                .andExpect(content().json("""
                        {"messaging_product":"whatsapp","recipient_type":"individual","to":"+96170123456",
                         "type":"template",
                         "template":{"name":"appointment_reminder","language":{"code":"ar"},
                           "components":[{"type":"body","parameters":[
                             {"type":"text","text":"Rana"},{"type":"text","text":"Dr. Khoury"},
                             {"type":"text","text":"Monday 2 November"},{"type":"text","text":"09:00"}]}]}}""",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess(SUCCESS, MediaType.APPLICATION_JSON));

        String id = client.send(ACCOUNT, new TemplateMessage("+96170123456", "appointment_reminder", "ar",
                List.of("Rana", "Dr. Khoury", "Monday 2 November", "09:00")));

        assertThat(id).isEqualTo("wamid.SENT-1");
        server.verify();
    }

    @Test
    void sendsText() {
        server.expect(requestTo(URL))
                .andExpect(content().json("""
                        {"messaging_product":"whatsapp","recipient_type":"individual","to":"+96170123456",
                         "type":"text","text":{"body":"Your appointment is cancelled."}}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess(SUCCESS, MediaType.APPLICATION_JSON));

        assertThat(client.send(ACCOUNT, new TextMessage("+96170123456", "Your appointment is cancelled.")))
                .isEqualTo("wamid.SENT-1");
    }

    @Test
    void sendsReplyButtons() {
        server.expect(requestTo(URL))
                .andExpect(content().json("""
                        {"messaging_product":"whatsapp","recipient_type":"individual","to":"+96170123456",
                         "type":"interactive",
                         "interactive":{"type":"button","body":{"text":"Which doctor?"},
                           "action":{"buttons":[
                             {"type":"reply","reply":{"id":"doctor-1","title":"Dr. Khoury"}},
                             {"type":"reply","reply":{"id":"doctor-2","title":"Dr. Saab"}}]}}}""",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess(SUCCESS, MediaType.APPLICATION_JSON));

        client.send(ACCOUNT, new ReplyButtonsMessage("+96170123456", "Which doctor?",
                List.of(new ReplyButton("doctor-1", "Dr. Khoury"), new ReplyButton("doctor-2", "Dr. Saab"))));

        server.verify();
    }

    @Test
    void metaRejectionsThatShouldNotBeRetried() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"error":{"message":"(#131026) Message undeliverable","type":"OAuthException","code":131026,
                         "error_data":{"messaging_product":"whatsapp","details":"Message Undeliverable."},
                         "fbtrace_id":"Az8or2yhqkZfEZ-_4Qn_Bam"}}"""));

        assertThatThrownBy(() -> client.send(ACCOUNT, new TextMessage("+96170123456", "hi")))
                .isInstanceOfSatisfying(WhatsAppException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(131026);
                    assertThat(e.getTitle()).isEqualTo("(#131026) Message undeliverable");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void rateLimitsAreRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"(#130429) Rate limit hit\",\"type\":\"OAuthException\",\"code\":130429}}"));

        assertThatThrownBy(() -> client.send(ACCOUNT, new TextMessage("+96170123456", "hi")))
                .isInstanceOfSatisfying(WhatsAppException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(130429);
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void serverErrorsAndNetworkErrorsAreRetryable() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client.send(ACCOUNT, new TextMessage("+96170123456", "hi")))
                .isInstanceOfSatisfying(WhatsAppException.class, e -> assertThat(e.isRetryable()).isTrue());

        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        assertThatThrownBy(() -> client.send(ACCOUNT, new TextMessage("+96170123456", "hi")))
                .isInstanceOfSatisfying(WhatsAppException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getCode()).isNull();
                });
    }

    @Test
    void refusesToSendWithoutAToken() {
        assertThatThrownBy(() -> client.send(new WhatsAppAccount("106540352242922", null),
                new TextMessage("+96170123456", "hi")))
                .isInstanceOfSatisfying(WhatsAppException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    @Test
    void theAccountNeverPrintsItsToken() {
        assertThat(ACCOUNT.toString()).doesNotContain("EAAG-token");
    }
}
