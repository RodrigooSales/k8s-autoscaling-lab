package org.csajavahttp.adapt;

import com.google.gson.Gson;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public final class TagAdaptationService {
    private static final List<String> TAGS = List.of("100k", "200k", "400k", "600k", "800k");
    private static final Gson JSON = new Gson();

    private final AdaptationStateService state;

    public TagAdaptationService(AdaptationStateService state) {
        this.state = state;
    }

    public Map<String, Object> adaptTag(boolean tagUp, boolean updateCpu) {
        V1Deployment deployment = state.deployment();
        if (state.rolloutInProgress(deployment)) {
            return Map.of("result", "skip");
        }
        V1Container target = targetContainer(deployment);
        String[] image = imageParts(target.getImage());
        if (image == null) {
            throw new AdaptationException("Could not identify tag in image " + target.getImage(), null);
        }
        String currentTag = image[1];
        Object stored = state.storedData("tag");
        String initialTag = stored == null || String.valueOf(stored).isBlank()
                ? currentTag
                : String.valueOf(stored);
        state.storeData("tag", currentTag);
        if (tagUp && TAGS.contains(initialTag) && currentTag.equals(initialTag)) {
            return Map.of("result", "skip");
        }
        int index = TAGS.indexOf(currentTag);
        int next = index + (tagUp ? 1 : -1);
        if (index < 0 || next < 0 || next >= TAGS.size()) {
            return Map.of("result", "skip");
        }
        String newTag = TAGS.get(next);
        List<Map<String, Object>> patches = new ArrayList<>();

        Integer currentCpu = null;
        boolean updateLimits = false;
        if (updateCpu) {
            int initialCpu = state.initialMcpu(deployment);
            List<V1Pod> pods = state.pods(deployment);
            currentCpu = state.currentMcpu(pods);
            updateLimits = currentCpu != null && currentCpu != initialCpu;
        }
        for (V1Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
            Map<String, Object> containerPatch = new LinkedHashMap<>();
            containerPatch.put("name", container.getName());
            if ("znn".equals(container.getName())) {
                containerPatch.put("image", image[0] + ":" + newTag);
            }
            if (updateLimits) {
                containerPatch.put("resources", Map.of("limits", Map.of("cpu", currentCpu + "m")));
            }
            patches.add(containerPatch);
        }
        state.patchDeployment(JSON.toJson(Map.of(
                "spec", Map.of("template", Map.of("spec", Map.of("containers", patches))))));
        return Map.of("tag", newTag);
    }

    private static V1Container targetContainer(V1Deployment deployment) {
        for (V1Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
            if ("znn".equals(container.getName())) {
                return container;
            }
        }
        throw new AdaptationException("Container 'znn' not found in Deployment", null);
    }

    private static String[] imageParts(String image) {
        if (image == null) {
            return null;
        }
        int colon = image.lastIndexOf(':');
        if (colon <= image.lastIndexOf('/') || colon == image.length() - 1) {
            return null;
        }
        String tag = image.substring(colon + 1);
        if (!tag.matches("[\\w.\\-]{1,127}")) {
            return null;
        }
        return new String[] {image.substring(0, colon), tag};
    }
}
