package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ResourceDetailsTest {
    @Test public void podExposesAddressesTimesNodeAndEveryContainerWithoutChoosingOne() throws Exception {
        PodDetails pod = new PodDetails(podFixture());
        assertEquals("web", pod.getPodName());
        assertEquals("team", pod.getNamespace());
        assertEquals("10.1.0.7", pod.getPodIP());
        assertEquals(Arrays.asList("10.1.0.7", "2001:db8::7"), pod.getPodIPs());
        assertEquals("192.0.2.7", pod.getHostIP());
        assertEquals(Arrays.asList("192.0.2.7", "2001:db8::10"), pod.getHostIPs());
        assertEquals("worker-a", pod.getNodeName());
        assertEquals("2026-09-21T00:00:00Z", pod.getCreationTimestamp());
        assertEquals("2026-09-21T00:00:01Z", pod.getStartTime());
        assertEquals("Running", pod.getPhase());
        assertEquals("Burstable", pod.getQosClass());
        assertEquals("reader", pod.getServiceAccountName());
        assertEquals(Boolean.FALSE, pod.getHostNetwork());
        assertEquals(Integer.valueOf(0), pod.getPriority());
        assertEquals(Long.valueOf(30), pod.getTerminationGracePeriodSeconds());
        assertEquals(Arrays.asList("app", "sidecar"), pod.getContainerNames());
        assertEquals(Collections.singletonList("init"), pod.getInitContainerNames());
        assertEquals(Collections.singletonList("debugger"), pod.getEphemeralContainerNames());
        assertEquals(Arrays.asList("example/app:v1", "example/proxy:v2"), pod.getImages());
        ContainerDetails app = pod.getContainers().get(0);
        assertEquals("app", app.getName());
        assertEquals("100m", app.getRequests().get("cpu"));
        assertEquals("128Mi", app.getLimits().get("memory"));
        assertEquals(Arrays.asList("/bin/sh", "-c"), app.getCommand());
        assertEquals("/ready", app.getReadinessProbe().getAsJsonObject("httpGet").get("path").getAsString());
        assertEquals("init", pod.getInitContainers().get(0).getName());
        assertEquals("debugger", pod.getEphemeralContainers().get(0).getName());
        assertEquals("True", pod.getConditions().get(0).getStatus());
        assertEquals("Unknown", pod.getConditions().get(1).getStatus());
    }

    @Test public void containerStatesDistinguishRunningWaitingTerminationAndPreviousAttempt() throws Exception {
        PodDetails pod = new PodDetails(podFixture());
        ContainerStatusDetails app = pod.getContainerStatuses().get(0);
        assertEquals(Boolean.TRUE, app.getReady());
        assertEquals(Boolean.TRUE, app.getStarted());
        assertEquals(Integer.valueOf(2), app.getRestartCount());
        assertEquals("containerd://123", app.getContainerID());
        assertEquals("running", app.getState().getType());
        assertEquals("2026-09-21T00:00:03Z", app.getStartedAt());
        assertNull(app.getExitCode());
        assertNull(app.getReason()); // 不将上次 OOMKilled 当成当前状态。
        assertEquals("terminated", app.getLastState().getType());
        assertEquals("OOMKilled", app.getLastState().getReason());
        assertEquals(Integer.valueOf(137), app.getLastState().getExitCode());
        assertNotNull(app.getLastState().getFinishedAt());
        ContainerStatusDetails waiting = pod.getContainerStatuses().get(1);
        assertEquals("waiting", waiting.getState().getType());
        assertEquals("ImagePullBackOff", waiting.getReason());
        assertNull(waiting.getStartedAt());
        assertEquals(Integer.valueOf(0), waiting.getRestartCount());
        assertEquals(Integer.valueOf(0), pod.getInitContainerStatuses().get(0).getExitCode());
        assertEquals(Integer.valueOf(1), pod.getEphemeralContainerStatuses().get(0).getExitCode());
        assertNull(new ContainerStateDetails(json("{\"running\":{},\"terminated\":{\"exitCode\":0}}" )).getType());
    }

    @Test public void missingAndMalformedFieldsAreSafeForEveryResourceType() throws Exception {
        List<Class<? extends ResourceDetails>> types = Arrays.asList(
                PodDetails.class, ConfigMapDetails.class, ServiceDetails.class, DeploymentDetails.class,
                StatefulSetDetails.class, DaemonSetDetails.class, ReplicaSetDetails.class, JobDetails.class,
                CronJobDetails.class, IngressDetails.class, PersistentVolumeClaimDetails.class,
                SecretDetails.class, ServiceAccountDetails.class, NetworkPolicyDetails.class);
        for (Class<? extends ResourceDetails> type : types) {
            for (String body : Arrays.asList("{}", "{\"spec\":null,\"status\":null,\"data\":null}",
                    "{\"spec\":[],\"status\":\"bad\",\"data\":1,\"immutable\":{}}")) {
                ResourceDetails result = type.getConstructor(JsonObject.class).newInstance(resource(body));
                for (Method method : type.getMethods()) {
                    if (!method.getName().startsWith("get") || method.getParameterCount() != 0) { continue; }
                    Object value = method.invoke(result); // 所有 getter 都不应对缺失 spec/status 抛空指针。
                    if (List.class.isAssignableFrom(method.getReturnType())) { assertNotNull(value); }
                    if (Map.class.isAssignableFrom(method.getReturnType())) { assertNotNull(value); }
                }
            }
        }
        PodDetails pending = new PodDetails(resource("{\"status\":{\"phase\":\"Pending\"}}"));
        assertNull(pending.getPodIP()); assertNull(pending.getNodeName()); assertNull(pending.getStartTime());
        assertNull(pending.getHostNetwork()); assertNull(pending.getPriority());
        assertTrue(pending.getContainerNames().isEmpty());
        assertThrows(NoSuchMethodException.class, () -> ConfigMapDetails.class.getMethod("getPodIP"));
        assertThrows(NoSuchMethodException.class, () -> ResourceDetails.class.getMethod("getContainerNames"));
        PodDetails malformed = new PodDetails(resource("{\"spec\":{\"priority\":2147483648,"
                + "\"hostNetwork\":\"false\",\"terminationGracePeriodSeconds\":1.5,"
                + "\"containers\":[null,3,{\"name\":\"app\"}]},\"status\":{\"podIP\":{}}}"));
        assertNull(malformed.getPriority()); assertNull(malformed.getHostNetwork());
        assertNull(malformed.getTerminationGracePeriodSeconds()); assertNull(malformed.getPodIP());
        assertEquals(Collections.singletonList("app"), malformed.getContainerNames());
    }

    @Test public void snapshotsAndNestedCollectionsCannotMutateResultsOrLeakThroughToString() throws Exception {
        JsonObject original = podFixture();
        PodDetails pod = new PodDetails(original);
        original.getAsJsonObject("status").addProperty("podIP", "changed");
        pod.toJson().remove("futureField");
        pod.getContainers().get(0).toJson().addProperty("name", "changed");
        pod.getContainers().get(0).getEnv().get(0).getAsJsonObject().addProperty("value", "changed");
        pod.getContainerStatuses().get(0).getLastState().toJson().remove("terminated");
        pod.getVolumes().get(0).getAsJsonObject().addProperty("name", "changed");
        pod.getOwnerReferences().get(0).getAsJsonObject().addProperty("name", "changed");
        assertEquals("10.1.0.7", pod.getPodIP());
        assertTrue(pod.toJson().has("futureField"));
        assertEquals("app", pod.getContainers().get(0).getName());
        assertEquals("private-password", pod.getContainers().get(0).getEnv().get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("OOMKilled", pod.getContainerStatuses().get(0).getLastState().getReason());
        assertEquals("data", pod.getVolumes().get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("web-rs", pod.getOwnerReferences().get(0).getAsJsonObject().get("name").getAsString());
        assertThrows(UnsupportedOperationException.class, () -> pod.getContainerNames().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> pod.getContainers().clear());
        assertThrows(UnsupportedOperationException.class, () -> pod.getContainers().get(0).getRequests().put("cpu", "9"));
        assertThrows(UnsupportedOperationException.class, () -> pod.getConditions().clear());
        for (Object result : Arrays.asList(pod, pod.getContainers(), pod.getContainerStatuses(),
                pod.getContainerStatuses().get(0).getLastState(), pod.getConditions())) {
            assertFalse(result.toString().contains("private-"));
        }
    }

    @Test public void serviceSupportsNamedAndNumericTargetPortsAndHeadlessAddresses() {
        ServiceDetails service = new ServiceDetails(resource("{\"spec\":{\"type\":\"NodePort\",\"clusterIP\":\"None\","
                + "\"clusterIPs\":[\"None\"],\"selector\":{\"app\":\"web\"},\"ports\":["
                + "{\"name\":\"web\",\"port\":80,\"targetPort\":\"http\",\"nodePort\":30080,\"protocol\":\"TCP\"},"
                + "{\"port\":443,\"targetPort\":8443}]},\"status\":{\"loadBalancer\":{\"ingress\":["
                + "{\"ip\":\"192.0.2.1\"},{\"hostname\":\"lb.example.com\"}]}}}"));
        assertEquals("NodePort", service.getType()); assertEquals("None", service.getClusterIP());
        assertEquals(Collections.singletonList("None"), service.getClusterIPs());
        assertEquals("web", service.getSelector().get("app"));
        assertEquals(Integer.valueOf(80), service.getPorts().get(0).getPort());
        assertEquals(Integer.valueOf(30080), service.getPorts().get(0).getNodePort());
        assertEquals("http", service.getPorts().get(0).getTargetPort());
        assertEquals("8443", service.getPorts().get(1).getTargetPort());
        assertNull(service.getPorts().get(1).getNodePort());
        assertEquals(Collections.singletonList("192.0.2.1"), service.getLoadBalancerIPs());
        assertEquals(Collections.singletonList("lb.example.com"), service.getLoadBalancerHostnames());
    }

    @Test public void configMapAndSecretExposeDataWithoutRequiringSpecOrStatus() {
        ConfigMapDetails config = new ConfigMapDetails(resource("{\"data\":{\"text\":\"private-config\"},"
                + "\"binaryData\":{\"file\":\"YWJj\"},\"immutable\":false}"));
        assertEquals("private-config", config.getDataMap().get("text"));
        assertEquals("YWJj", config.getBinaryDataMap().get("file"));
        assertEquals(Boolean.FALSE, config.getImmutable());
        config.getData().addProperty("text", "changed");
        assertEquals("private-config", config.getDataMap().get("text"));
        assertTrue(config.getSpec().entrySet().isEmpty()); assertTrue(config.getStatus().entrySet().isEmpty());
        assertFalse(config.toString().contains("private-config"));
        SecretDetails secret = new SecretDetails(resource("{\"type\":\"Opaque\",\"immutable\":true,\"data\":{\"password\":\"c2VjcmV0\"}}"));
        assertEquals("Opaque", secret.getType()); assertEquals(Boolean.TRUE, secret.getImmutable());
        assertEquals("c2VjcmV0", secret.getDataMap().get("password"));
        assertFalse(secret.toString().contains("c2VjcmV0"));
        assertThrows(UnsupportedOperationException.class, () -> secret.getDataMap().clear());
    }

    @Test public void workloadReplicasAndTemplatesHaveResourceSpecificMeaning() {
        JsonObject input = resource("{\"spec\":{\"replicas\":5,\"selector\":{\"matchLabels\":{\"app\":\"web\"}},"
                + "\"template\":{\"spec\":{\"containers\":[{\"name\":\"app\",\"image\":\"example:v1\"}]}}},"
                + "\"status\":{\"replicas\":4,\"currentReplicas\":2,\"readyReplicas\":3,\"updatedReplicas\":1,"
                + "\"observedGeneration\":4294967296,\"currentRevision\":\"rev-1\",\"updateRevision\":\"rev-2\"}}" );
        DeploymentDetails deployment = new DeploymentDetails(input);
        assertEquals(Integer.valueOf(5), deployment.getReplicas());
        assertEquals(Integer.valueOf(4), deployment.getCurrentReplicas());
        assertEquals(Integer.valueOf(3), deployment.getReadyReplicas());
        assertEquals(Long.valueOf(4294967296L), deployment.getObservedGeneration());
        assertEquals(Collections.singletonList("app"), deployment.getContainerNames());
        assertEquals("example:v1", deployment.getContainers().get(0).getImage());
        assertEquals("web", deployment.getMatchLabels().get("app"));
        StatefulSetDetails stateful = new StatefulSetDetails(input);
        assertEquals(Integer.valueOf(2), stateful.getCurrentReplicas());
        assertEquals(Integer.valueOf(4), stateful.getObservedReplicas());
        assertEquals("rev-1", stateful.getCurrentRevision()); assertEquals("rev-2", stateful.getUpdateRevision());
        ReplicaSetDetails replica = new ReplicaSetDetails(input);
        assertEquals(Integer.valueOf(5), replica.getReplicas()); assertEquals(Integer.valueOf(4), replica.getCurrentReplicas());
        DaemonSetDetails daemon = new DaemonSetDetails(resource("{\"status\":{\"desiredNumberScheduled\":4,\"numberReady\":3,\"numberUnavailable\":1}}"));
        assertEquals(Integer.valueOf(4), daemon.getDesiredNumberScheduled());
        assertEquals(Integer.valueOf(3), daemon.getNumberReady());
        assertEquals(Integer.valueOf(1), daemon.getNumberUnavailable());
    }

    @Test public void jobsStorageIngressAndPolicyUseTheirOwnJsonLayouts() {
        JobDetails job = new JobDetails(resource("{\"spec\":{\"parallelism\":2,\"completions\":4},"
                + "\"status\":{\"active\":1,\"succeeded\":3,\"failed\":0,\"completionTime\":\"2026-09-21T01:00:00Z\"}}"));
        assertEquals(Integer.valueOf(2), job.getParallelism()); assertEquals(Integer.valueOf(4), job.getCompletions());
        assertEquals(Integer.valueOf(1), job.getActive()); assertEquals(Integer.valueOf(3), job.getSucceeded());
        assertEquals(Integer.valueOf(0), job.getFailed()); assertNotNull(job.getCompletionTime());
        CronJobDetails cron = new CronJobDetails(resource("{\"spec\":{\"schedule\":\"0 * * * *\",\"suspend\":false,"
                + "\"jobTemplate\":{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"batch\"}]}}}}},"
                + "\"status\":{\"lastScheduleTime\":\"2026-09-21T00:00:00Z\"}}"));
        assertEquals("0 * * * *", cron.getSchedule()); assertEquals(Boolean.FALSE, cron.getSuspend());
        assertEquals(Collections.singletonList("batch"), cron.getContainerNames());
        assertEquals("batch", cron.getContainers().get(0).getName()); assertNotNull(cron.getLastScheduleTime());
        PersistentVolumeClaimDetails pvc = new PersistentVolumeClaimDetails(resource("{\"spec\":{\"storageClassName\":\"fast\","
                + "\"volumeName\":\"pv-1\",\"accessModes\":[\"ReadWriteOnce\"],\"resources\":{\"requests\":{\"storage\":\"1Gi\"}}},"
                + "\"status\":{\"phase\":\"Bound\",\"capacity\":{\"storage\":\"2Gi\"}}}"));
        assertEquals("fast", pvc.getStorageClassName()); assertEquals("pv-1", pvc.getVolumeName());
        assertEquals("1Gi", pvc.getRequestedStorage()); assertEquals("2Gi", pvc.getCapacityStorage());
        assertEquals("Bound", pvc.getPhase()); assertEquals(Collections.singletonList("ReadWriteOnce"), pvc.getAccessModes());
        IngressDetails ingress = new IngressDetails(resource("{\"spec\":{\"ingressClassName\":\"nginx\","
                + "\"rules\":[{\"host\":\"app.example.com\"},{\"http\":{}}],\"tls\":[{\"secretName\":\"tls\"}]}}"));
        assertEquals("nginx", ingress.getIngressClassName());
        assertEquals(Collections.singletonList("app.example.com"), ingress.getHosts()); assertEquals(2, ingress.getRules().size());
        ServiceAccountDetails sa = new ServiceAccountDetails(resource("{\"automountServiceAccountToken\":false,"
                + "\"secrets\":[{\"name\":\"token\"}],\"imagePullSecrets\":[{\"name\":\"registry\"}]}"));
        assertEquals(Boolean.FALSE, sa.getAutomountServiceAccountToken());
        assertEquals(Collections.singletonList("token"), sa.getSecretNames());
        assertEquals(Collections.singletonList("registry"), sa.getImagePullSecretNames());
        NetworkPolicyDetails policy = new NetworkPolicyDetails(resource("{\"spec\":{\"podSelector\":{\"matchLabels\":{\"app\":\"web\"}},"
                + "\"policyTypes\":[\"Ingress\",\"Egress\"],\"ingress\":[{}],\"egress\":[]}}"));
        assertEquals("web", policy.getMatchLabels().get("app"));
        assertEquals(Arrays.asList("Ingress", "Egress"), policy.getPolicyTypes());
        assertEquals(1, policy.getIngress().size()); assertEquals(0, policy.getEgress().size());
    }

    private static JsonObject resource(String body) {
        JsonObject result = json(body);
        result.add("metadata", json("{\"namespace\":\"team\",\"name\":\"web\"}"));
        return result;
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private static JsonObject podFixture() throws Exception {
        try (InputStream stream = ResourceDetailsTest.class.getResourceAsStream("/details/pod.json");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
