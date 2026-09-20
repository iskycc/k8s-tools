package com.iskycc.k8s.api;

/** 常见内置资源的 GVR 常量；实际可用版本和动作请查询 Discovery，CRD 可自行定义。 */
public final class K8sResources {
    private K8sResources() { }

    public static final ResourceDefinition NAMESPACES = ResourceDefinition.cluster("v1", "namespaces", "Namespace");
    public static final ResourceDefinition NODES = ResourceDefinition.cluster("v1", "nodes", "Node");
    public static final ResourceDefinition PODS = ResourceDefinition.namespaced("v1", "pods", "Pod");
    public static final ResourceDefinition SERVICES = ResourceDefinition.namespaced("v1", "services", "Service");
    public static final ResourceDefinition CONFIG_MAPS = ResourceDefinition.namespaced("v1", "configmaps", "ConfigMap");
    public static final ResourceDefinition SECRETS = ResourceDefinition.namespaced("v1", "secrets", "Secret");
    public static final ResourceDefinition SERVICE_ACCOUNTS = ResourceDefinition.namespaced("v1", "serviceaccounts", "ServiceAccount");
    public static final ResourceDefinition ENDPOINTS = ResourceDefinition.namespaced("v1", "endpoints", "Endpoints");
    public static final ResourceDefinition EVENTS = ResourceDefinition.namespaced("v1", "events", "Event");
    public static final ResourceDefinition PERSISTENT_VOLUMES = ResourceDefinition.cluster("v1", "persistentvolumes", "PersistentVolume");
    public static final ResourceDefinition PERSISTENT_VOLUME_CLAIMS = ResourceDefinition.namespaced("v1", "persistentvolumeclaims", "PersistentVolumeClaim");
    public static final ResourceDefinition RESOURCE_QUOTAS = ResourceDefinition.namespaced("v1", "resourcequotas", "ResourceQuota");
    public static final ResourceDefinition LIMIT_RANGES = ResourceDefinition.namespaced("v1", "limitranges", "LimitRange");
    public static final ResourceDefinition REPLICATION_CONTROLLERS = ResourceDefinition.namespaced("v1", "replicationcontrollers", "ReplicationController");
    public static final ResourceDefinition DEPLOYMENTS = ResourceDefinition.namespaced("apps/v1", "deployments", "Deployment");
    public static final ResourceDefinition STATEFUL_SETS = ResourceDefinition.namespaced("apps/v1", "statefulsets", "StatefulSet");
    public static final ResourceDefinition DAEMON_SETS = ResourceDefinition.namespaced("apps/v1", "daemonsets", "DaemonSet");
    public static final ResourceDefinition REPLICA_SETS = ResourceDefinition.namespaced("apps/v1", "replicasets", "ReplicaSet");
    public static final ResourceDefinition CONTROLLER_REVISIONS = ResourceDefinition.namespaced("apps/v1", "controllerrevisions", "ControllerRevision");
    public static final ResourceDefinition JOBS = ResourceDefinition.namespaced("batch/v1", "jobs", "Job");
    public static final ResourceDefinition CRON_JOBS = ResourceDefinition.namespaced("batch/v1", "cronjobs", "CronJob");
    public static final ResourceDefinition INGRESSES = ResourceDefinition.namespaced("networking.k8s.io/v1", "ingresses", "Ingress");
    public static final ResourceDefinition INGRESS_CLASSES = ResourceDefinition.cluster("networking.k8s.io/v1", "ingressclasses", "IngressClass");
    public static final ResourceDefinition NETWORK_POLICIES = ResourceDefinition.namespaced("networking.k8s.io/v1", "networkpolicies", "NetworkPolicy");
    public static final ResourceDefinition ENDPOINT_SLICES = ResourceDefinition.namespaced("discovery.k8s.io/v1", "endpointslices", "EndpointSlice");
    public static final ResourceDefinition ROLES = ResourceDefinition.namespaced("rbac.authorization.k8s.io/v1", "roles", "Role");
    public static final ResourceDefinition ROLE_BINDINGS = ResourceDefinition.namespaced("rbac.authorization.k8s.io/v1", "rolebindings", "RoleBinding");
    public static final ResourceDefinition CLUSTER_ROLES = ResourceDefinition.cluster("rbac.authorization.k8s.io/v1", "clusterroles", "ClusterRole");
    public static final ResourceDefinition CLUSTER_ROLE_BINDINGS = ResourceDefinition.cluster("rbac.authorization.k8s.io/v1", "clusterrolebindings", "ClusterRoleBinding");
    public static final ResourceDefinition TOKEN_REVIEWS = ResourceDefinition.cluster("authentication.k8s.io/v1", "tokenreviews", "TokenReview");
    public static final ResourceDefinition SUBJECT_ACCESS_REVIEWS = ResourceDefinition.cluster("authorization.k8s.io/v1", "subjectaccessreviews", "SubjectAccessReview");
    public static final ResourceDefinition SELF_SUBJECT_ACCESS_REVIEWS = ResourceDefinition.cluster("authorization.k8s.io/v1", "selfsubjectaccessreviews", "SelfSubjectAccessReview");
    public static final ResourceDefinition LOCAL_SUBJECT_ACCESS_REVIEWS = ResourceDefinition.namespaced("authorization.k8s.io/v1", "localsubjectaccessreviews", "LocalSubjectAccessReview");
    public static final ResourceDefinition SELF_SUBJECT_RULES_REVIEWS = ResourceDefinition.cluster("authorization.k8s.io/v1", "selfsubjectrulesreviews", "SelfSubjectRulesReview");
    public static final ResourceDefinition STORAGE_CLASSES = ResourceDefinition.cluster("storage.k8s.io/v1", "storageclasses", "StorageClass");
    public static final ResourceDefinition CSI_DRIVERS = ResourceDefinition.cluster("storage.k8s.io/v1", "csidrivers", "CSIDriver");
    public static final ResourceDefinition CSI_NODES = ResourceDefinition.cluster("storage.k8s.io/v1", "csinodes", "CSINode");
    public static final ResourceDefinition CSI_STORAGE_CAPACITIES = ResourceDefinition.namespaced("storage.k8s.io/v1", "csistoragecapacities", "CSIStorageCapacity");
    public static final ResourceDefinition VOLUME_ATTACHMENTS = ResourceDefinition.cluster("storage.k8s.io/v1", "volumeattachments", "VolumeAttachment");
    public static final ResourceDefinition HORIZONTAL_POD_AUTOSCALERS = ResourceDefinition.namespaced("autoscaling/v2", "horizontalpodautoscalers", "HorizontalPodAutoscaler");
    public static final ResourceDefinition POD_DISRUPTION_BUDGETS = ResourceDefinition.namespaced("policy/v1", "poddisruptionbudgets", "PodDisruptionBudget");
    public static final ResourceDefinition PRIORITY_CLASSES = ResourceDefinition.cluster("scheduling.k8s.io/v1", "priorityclasses", "PriorityClass");
    public static final ResourceDefinition RUNTIME_CLASSES = ResourceDefinition.cluster("node.k8s.io/v1", "runtimeclasses", "RuntimeClass");
    public static final ResourceDefinition LEASES = ResourceDefinition.namespaced("coordination.k8s.io/v1", "leases", "Lease");
    public static final ResourceDefinition CUSTOM_RESOURCE_DEFINITIONS = ResourceDefinition.cluster("apiextensions.k8s.io/v1", "customresourcedefinitions", "CustomResourceDefinition");
    public static final ResourceDefinition API_SERVICES = ResourceDefinition.cluster("apiregistration.k8s.io/v1", "apiservices", "APIService");
    public static final ResourceDefinition CERTIFICATE_SIGNING_REQUESTS = ResourceDefinition.cluster("certificates.k8s.io/v1", "certificatesigningrequests", "CertificateSigningRequest");
    public static final ResourceDefinition MUTATING_WEBHOOK_CONFIGURATIONS = ResourceDefinition.cluster("admissionregistration.k8s.io/v1", "mutatingwebhookconfigurations", "MutatingWebhookConfiguration");
    public static final ResourceDefinition VALIDATING_WEBHOOK_CONFIGURATIONS = ResourceDefinition.cluster("admissionregistration.k8s.io/v1", "validatingwebhookconfigurations", "ValidatingWebhookConfiguration");
}
