package org.csajava.runtime.adapt.tag;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.PatchUtils;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.adapt.AdaptSupport;
import org.csajava.runtime.initialdata.InitialDataStore;
import org.csajava.util.JsonUtil;

public final class AdaptTagRuntime {
    private static final List<String> TAGS = List.of("100k", "200k", "400k", "600k", "800k");

    private static final String PARAM_TAG_UP = "tag_up";
    private static final String PARAM_UPDATE_CPU = "update_cpu";

    private static final String CONTAINER_ZNN = "znn";
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "^(?<repository>[\\w.\\-_]+((?::\\d+|)(?=/[a-z0-9._-]+/[a-z0-9._-]+))|)"
                    + "(?:/|)(?<image>[a-z0-9.\\-_]+(?:/[a-z0-9.\\-_]+|))"
                    + "(?::(?<tag>[\\w.\\-_]{1,127})|)$",
            Pattern.UNICODE_CHARACTER_CLASS);

    private AdaptTagRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        if (!context.hints().isEmpty()) {
            Boolean tagUp = JsonUtil.boolPath(context.stdinJson(), "evaluation", "parameters", PARAM_TAG_UP);
            if (tagUp == null) {
                return AdaptSupport.error();
            }
            boolean updateCpu = Boolean.TRUE.equals(
                    JsonUtil.boolPath(context.stdinJson(), "evaluation", "parameters", PARAM_UPDATE_CPU));
            return evaluateFromHints(context, tagUp, updateCpu);
        }
        return evaluateInCluster(context);
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

    private static Object evaluateInCluster(RuntimeContext context) {
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
            InitialDataStore store = new InitialDataStore(context, core, customObjects);

            return adaptInCluster(context, client, apps, core, store);
        } catch (Exception e) {
            return AdaptSupport.error();
        }
    }

    static Object adaptInCluster(
            RuntimeContext context,
            ApiClient client,
            AppsV1Api apps,
            CoreV1Api core,
            InitialDataStore store) {
        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        try {
            V1Deployment deployment = apps.readNamespacedDeployment(name, namespace).execute();
            if (deployment == null) {
                return AdaptSupport.error();
            }

            Boolean tagUp = JsonUtil.boolPath(context.stdinJson(), "evaluation", "parameters", PARAM_TAG_UP);
            if (tagUp == null) {
                return AdaptSupport.error();
            }
            boolean updateCpu = Boolean.TRUE.equals(
                    JsonUtil.boolPath(context.stdinJson(), "evaluation", "parameters", PARAM_UPDATE_CPU));

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
                Object initialMcpu = store.getStoredCpuLimitValue();
                if (initialMcpu == null) {
                    Integer specMcpu = AdaptSupport.specMcpu(deployment);
                    store.storeCpuLimit(specMcpu);
                    initialMcpu = specMcpu;
                }

                Integer currentMcpu = AdaptSupport.currentMcpu(AdaptSupport.runningPods(core, namespace, deployment));
                if (currentMcpu != null && shouldUpdateCpu(initialMcpu, currentMcpu)
                        && deployment.getSpec() != null
                        && deployment.getSpec().getTemplate() != null
                        && deployment.getSpec().getTemplate().getSpec() != null
                        && deployment.getSpec().getTemplate().getSpec().getContainers() != null) {
                    for (V1Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
                        AdaptSupport.ensureContainerCpuLimit(container, currentMcpu);
                    }
                }
            }

            V1Patch patch = new V1Patch(JSON.serialize(deployment));
            PatchUtils.patch(
                    V1Deployment.class,
                    () -> apps.patchNamespacedDeployment(name, namespace, patch).buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    client);
            return AdaptSupport.tag(newTag);
        } catch (ApiException e) {
            return AdaptSupport.error();
        } catch (Exception e) {
            return AdaptSupport.error();
        }
    }

    static boolean shouldUpdateCpu(Object initialMcpu, int currentMcpu) {
        if (initialMcpu instanceof Number number) {
            return number.doubleValue() != currentMcpu;
        }
        if (initialMcpu instanceof Boolean bool) {
            return (bool ? 1 : 0) != currentMcpu;
        }
        return true;
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
        ImageParts parts = imageParts(image);
        return parts == null ? null : parts.tag();
    }

    static String replaceTag(String image, String tag) {
        ImageParts parts = imageParts(image);
        if (parts == null) {
            return image;
        }
        String prefix = parts.repository().isEmpty() ? "" : parts.repository() + "/";
        return prefix + parts.image() + ":" + tag;
    }

    private static ImageParts imageParts(String image) {
        if (image == null) {
            return null;
        }
        Matcher matcher = IMAGE_PATTERN.matcher(image);
        if (!matcher.matches()) {
            return null;
        }
        return new ImageParts(matcher.group("repository"), matcher.group("image"), matcher.group("tag"));
    }

    private record ImageParts(String repository, String image, String tag) {
    }
}
