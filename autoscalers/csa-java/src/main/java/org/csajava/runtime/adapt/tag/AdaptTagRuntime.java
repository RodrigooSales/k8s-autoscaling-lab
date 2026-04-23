package org.csajava.runtime.adapt.tag;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.Config;
import java.util.List;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.adapt.AdaptSupport;
import org.csajava.runtime.initialdata.InitialDataStore;
import org.csajava.util.JsonUtil;

public final class AdaptTagRuntime {
    private static final List<String> TAGS = List.of("100k", "200k", "400k", "600k", "800k");

    private static final String PARAM_TAG_UP = "tag_up";
    private static final String PARAM_UPDATE_CPU = "update_cpu";

    private static final String CONTAINER_ZNN = "znn";

    private AdaptTagRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        Boolean tagUp = JsonUtil.boolPath(context.stdinJson(), "evaluation", "parameters", PARAM_TAG_UP);
        if (tagUp == null) {
            return AdaptSupport.error();
        }
        boolean updateCpu = Boolean.TRUE.equals(
                JsonUtil.boolPath(context.stdinJson(), "evaluation", "parameters", PARAM_UPDATE_CPU));

        if (!context.hints().isEmpty()) {
            return evaluateFromHints(context, tagUp, updateCpu);
        }
        return evaluateInCluster(context, tagUp, updateCpu);
    }

    private static Object evaluateFromHints(RuntimeContext context, boolean tagUp, boolean updateCpu) {
        if (AdaptSupport.hintBool(context, "rollout_in_progress", false)) {
            return AdaptSupport.skip();
        }

        String image = AdaptSupport.hintString(context, "container_znn_image");
        String currentTag = extractTag(image);
        if (currentTag == null) {
            return AdaptSupport.error();
        }

        InitialDataStore store = new InitialDataStore(context, null, null);
        store.storeTag(currentTag);

        if (tagUp) {
            String initialTag = store.getStoredTag();
            if (initialTag != null && TAGS.contains(initialTag) && currentTag.equals(initialTag)) {
                return AdaptSupport.skip();
            }
        }

        String newTag = adjacentTag(currentTag, tagUp);
        if (newTag == null) {
            return AdaptSupport.skip();
        }

        if (updateCpu) {
            Integer initialMcpu = store.getStoredCpuLimit();
            if (initialMcpu == null) {
                Integer specMcpu = AdaptSupport.hintInt(context, "spec_mcpu");
                store.storeCpuLimit(specMcpu);
            }
        }

        boolean patchSuccess = AdaptSupport.hintBool(context, "deployment_patch_success", true);
        return patchSuccess ? AdaptSupport.tag(newTag) : AdaptSupport.error();
    }

    private static Object evaluateInCluster(RuntimeContext context, boolean tagUp, boolean updateCpu) {
        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        if (name == null || namespace == null) {
            return AdaptSupport.error();
        }

        try {
            ApiClient client = Config.fromCluster();
            AppsV1Api apps = new AppsV1Api(client);
            CoreV1Api core = new CoreV1Api(client);
            CustomObjectsApi customObjects = new CustomObjectsApi(client);

            V1Deployment deployment = apps.readNamespacedDeployment(name, namespace).execute();
            if (deployment == null) {
                return AdaptSupport.error();
            }

            if (AdaptSupport.rolloutInProgress(deployment)) {
                return AdaptSupport.skip();
            }

            V1Container znn = AdaptSupport.findContainer(deployment, CONTAINER_ZNN);
            if (znn == null || znn.getImage() == null || znn.getImage().isBlank()) {
                return AdaptSupport.error();
            }

            String currentTag = extractTag(znn.getImage());
            if (currentTag == null) {
                return AdaptSupport.error();
            }

            InitialDataStore store = new InitialDataStore(context, core, customObjects);
            store.storeTag(currentTag);

            if (tagUp) {
                String initialTag = store.getStoredTag();
                if (initialTag != null && TAGS.contains(initialTag) && currentTag.equals(initialTag)) {
                    return AdaptSupport.skip();
                }
            }

            String newTag = adjacentTag(currentTag, tagUp);
            if (newTag == null) {
                return AdaptSupport.skip();
            }

            znn.setImage(replaceTag(znn.getImage(), newTag));

            if (updateCpu) {
                Integer initialMcpu = store.getStoredCpuLimit();
                if (initialMcpu == null) {
                    initialMcpu = AdaptSupport.specMcpu(deployment);
                    store.storeCpuLimit(initialMcpu);
                }

                Integer currentMcpu = AdaptSupport.currentMcpu(AdaptSupport.runningPods(core, namespace, deployment));
                if (currentMcpu != null && initialMcpu != null && !currentMcpu.equals(initialMcpu)
                        && deployment.getSpec() != null
                        && deployment.getSpec().getTemplate() != null
                        && deployment.getSpec().getTemplate().getSpec() != null
                        && deployment.getSpec().getTemplate().getSpec().getContainers() != null) {
                    for (V1Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
                        AdaptSupport.ensureContainerCpuLimit(container, currentMcpu);
                    }
                }
            }

            apps.replaceNamespacedDeployment(name, namespace, deployment).execute();
            return AdaptSupport.tag(newTag);
        } catch (Exception e) {
            return AdaptSupport.error();
        }
    }

    static String adjacentTag(String currentTag, boolean up) {
        int index = TAGS.indexOf(currentTag);
        if (index < 0) {
            return null;
        }

        int next = up ? index + 1 : index - 1;
        if (next < 0 || next >= TAGS.size()) {
            return null;
        }
        return TAGS.get(next);
    }

    static String extractTag(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }

        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        if (colon <= slash || colon == image.length() - 1) {
            return null;
        }
        return image.substring(colon + 1);
    }

    static String replaceTag(String image, String tag) {
        if (image == null || image.isBlank() || tag == null || tag.isBlank()) {
            return image;
        }

        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        String withoutTag = colon > slash ? image.substring(0, colon) : image;
        return withoutTag + ":" + tag;
    }
}
