package app.clinicreminders.whatsapp;

/**
 * The only way messages leave the application. {@code CloudApiWhatsAppClient} talks to Meta;
 * {@code FakeWhatsAppClient} (dev and tests) records messages instead. Callers go through
 * {@link WhatsAppMessenger}, which adds opt-out checks, retries and the message log.
 */
public interface WhatsAppClient {

    /**
     * @return the WhatsApp message id ({@code wamid...}) that later status webhooks refer to
     * @throws WhatsAppException if Meta rejected the message or couldn't be reached
     */
    String send(WhatsAppAccount account, OutboundMessage message);

    /**
     * Subscribes our Meta app to a clinic's WhatsApp Business Account, so Meta sends the webhooks of
     * its phone numbers to us. Needed once per account during onboarding.
     *
     * @throws WhatsAppException if Meta refused (e.g. the token lacks whatsapp_business_management)
     */
    void subscribeApp(String businessAccountId, String accessToken);
}
