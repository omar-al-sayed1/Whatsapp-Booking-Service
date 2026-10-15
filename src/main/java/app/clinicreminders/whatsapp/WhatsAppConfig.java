package app.clinicreminders.whatsapp;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import app.clinicreminders.common.Sleeper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Picks the WhatsApp client with {@code whatsapp.client}: {@code cloud} (default) or {@code fake}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WhatsAppProperties.class)
class WhatsAppConfig {

    @Bean
    @ConditionalOnProperty(name = "whatsapp.client", havingValue = "cloud", matchIfMissing = true)
    WhatsAppClient cloudApiWhatsAppClient(WhatsAppProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl().toString())
                .build();
        return new CloudApiWhatsAppClient(restClient, properties.graphVersion());
    }

    @Bean
    @ConditionalOnProperty(name = "whatsapp.client", havingValue = "fake")
    FakeWhatsAppClient fakeWhatsAppClient(Clock clock) {
        return new FakeWhatsAppClient(clock);
    }

    @Bean
    TokenCipher tokenCipher(WhatsAppProperties properties) {
        return new TokenCipher(properties.tokenEncryptionKey());
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.THREAD;
    }
}
