package com.iskycc.k8s;

import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;

/**
 * 面向使用方的统一初始化工具。每次明确指定目标实例，返回完整的 K8sApiClient。
 * 不保存全局“当前集群”；不同客户端可以同时调用不同集群。
 * Redis 连接由客户端初始化流程管理，返回后无需 close。
 */
public final class K8sTools {
    private K8sTools() { }

    /**
     * 初始化目标集群：优先复用 Redis 缓存，未命中时通过密码 SSH 获取凭据与 API 地址。
     * 默认跳过 API 证书及主机名校验；SSH 初始化可能创建/重建 SA、Secret 和 RBAC。
     * 返回值支持全部已有查询、搜索、CRUD、Discovery、分页、子资源及 Pod Exec 方法。
     *
     * @param instance 目标集群及 Redis 配置
     * @return 完整 SDK 客户端，无需 close
     */
    public static K8sApiClient init(K8sInstance instance) {
        return init(instance, null);
    }

    /** 自定义凭据获取选项；不修改 options，实例配置的 Redis 优先于旧外部缓存。 */
    public static K8sApiClient init(K8sInstance instance, ServiceTokenFetcher.Options options) {
        return connect(instance, options, false);
    }

    /**
     * 显式删除该 master 的缓存，重新通过 SSH 获取凭据并返回新客户端。
     * 不修改已有客户端，不重放业务请求；删除后获取失败时缓存保持删除状态。
     */
    public static K8sApiClient refresh(K8sInstance instance) {
        return refresh(instance, null);
    }

    /** 使用与初始化相同的自定义获取选项刷新凭据，返回新客户端。 */
    public static K8sApiClient refresh(K8sInstance instance, ServiceTokenFetcher.Options options) {
        return connect(instance, options, true);
    }

    private static K8sApiClient connect(K8sInstance instance, ServiceTokenFetcher.Options options, boolean refresh) {
        if (instance == null) { throw new IllegalArgumentException("K8sInstance is required"); }
        return K8sApiClient.builder().redisUrl(instance.redisUrl()).refreshCache(refresh)
                .fromSsh(instance.sshConfig(), options);
    }
}
