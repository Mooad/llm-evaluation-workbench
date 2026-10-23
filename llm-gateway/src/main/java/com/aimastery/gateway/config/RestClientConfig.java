package com.aimastery.gateway.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Un builder par fournisseur : chacun a son propre timeout, defini en configuration.
     */
    @Bean
    public RestClientFactory restClientFactory() {
        return (baseUrl, timeout) -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            factory.setReadTimeout((int) timeout.toMillis());
            return RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .build();
        };
    }

    @FunctionalInterface
    public interface RestClientFactory {
        RestClient create(String baseUrl, Duration timeout);
    }
}
