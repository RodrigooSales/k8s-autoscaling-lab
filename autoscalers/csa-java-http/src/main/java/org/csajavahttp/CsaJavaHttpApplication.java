package org.csajavahttp;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

@SpringBootApplication
public class CsaJavaHttpApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsaJavaHttpApplication.class, args);
    }

    @Bean
    @Lazy
    ApiClient kubernetesApiClient() throws IOException {
        return Config.defaultClient();
    }
}
