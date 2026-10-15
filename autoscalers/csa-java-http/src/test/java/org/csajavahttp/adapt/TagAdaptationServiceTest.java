package org.csajavahttp.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TagAdaptationServiceTest {
    private static final Gson JSON = new Gson();

    @Test
    void patchesTheNextLowerTag() {
        AdaptationStateService state = mock(AdaptationStateService.class);
        when(state.deployment()).thenReturn(deployment("registry.k8s.lab/kube-znn:600k"));
        when(state.rolloutInProgress(any())).thenReturn(false);
        when(state.storedData("tag")).thenReturn("100k");

        var result = new TagAdaptationService(state).adaptTag(false, false);

        assertEquals(Map.of("tag", "400k"), result);
        ArgumentCaptor<String> patch = ArgumentCaptor.forClass(String.class);
        verify(state).patchDeployment(patch.capture());
        Map<?, ?> decoded = JSON.fromJson(patch.getValue(), Map.class);
        Map<?, ?> spec = (Map<?, ?>) decoded.get("spec");
        Map<?, ?> template = (Map<?, ?>) spec.get("template");
        Map<?, ?> podSpec = (Map<?, ?>) template.get("spec");
        Map<?, ?> container = (Map<?, ?>) ((List<?>) podSpec.get("containers")).get(0);
        assertEquals("registry.k8s.lab/kube-znn:400k", container.get("image"));
        verify(state).storeData("tag", "600k");
    }

    @Test
    void synchronizesCurrentCpuIntoTheDeploymentWhenRequested() {
        AdaptationStateService state = mock(AdaptationStateService.class);
        V1Deployment deployment = deployment("registry.k8s.lab/kube-znn:600k");
        when(state.deployment()).thenReturn(deployment);
        when(state.rolloutInProgress(any())).thenReturn(false);
        when(state.storedData("tag")).thenReturn("100k");
        when(state.initialMcpu(deployment)).thenReturn(500);
        when(state.pods(deployment)).thenReturn(List.of(new V1Pod()));
        when(state.currentMcpu(anyList())).thenReturn(650);

        var result = new TagAdaptationService(state).adaptTag(false, true);

        assertEquals(Map.of("tag", "400k"), result);
        ArgumentCaptor<String> patch = ArgumentCaptor.forClass(String.class);
        verify(state).patchDeployment(patch.capture());
        assertTrue(patch.getValue().contains("\"cpu\":\"650m\""));
        assertTrue(patch.getValue().contains("\"image\":\"registry.k8s.lab/kube-znn:400k\""));
    }

    @Test
    void doesNotMoveAboveTheStoredInitialTag() {
        AdaptationStateService state = mock(AdaptationStateService.class);
        when(state.deployment()).thenReturn(deployment("registry.k8s.lab/kube-znn:400k"));
        when(state.rolloutInProgress(any())).thenReturn(false);
        when(state.storedData("tag")).thenReturn("400k");

        var result = new TagAdaptationService(state).adaptTag(true, false);

        assertEquals(Map.of("result", "skip"), result);
        verify(state, never()).patchDeployment(any());
    }

    private static V1Deployment deployment(String image) {
        return new V1Deployment().spec(new V1DeploymentSpec().template(new V1PodTemplateSpec().spec(
                new V1PodSpec().containers(List.of(
                        new V1Container().name("znn").image(image),
                        new V1Container().name("nginx").image("nginx:stable"))))));
    }
}
