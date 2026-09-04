package org.csajava.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.io.IOException;

public final class KubernetesClient {
    private KubernetesClient() {
    }

    public static ApiClient load() throws IOException {
        String url = System.getenv("CSA_KUBERNETES_URL");
        return url == null || url.isBlank() ? Config.fromCluster() : Config.fromUrl(url, false);
    }
}
