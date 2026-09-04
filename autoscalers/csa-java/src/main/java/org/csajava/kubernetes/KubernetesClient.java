package org.csajava.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.io.IOException;

public final class KubernetesClient {
    private KubernetesClient() {
    }

    public static ApiClient load() throws IOException {
        return Config.fromCluster();
    }
}
