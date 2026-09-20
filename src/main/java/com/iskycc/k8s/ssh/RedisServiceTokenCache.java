package com.iskycc.k8s.ssh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sToolsException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

import java.util.List;

/**
 * 使用 Redis String 保存 master 的 token 与 API 地址，不设置过期时间。
 * 连接池由调用方管理；每次操作只借用连接，用完归还，不关闭共享连接池。
 * 支持单实例 Redis，不支持跨槽 Redis Cluster。
 */
public final class RedisServiceTokenCache {

    private final JedisPool pool;

    public RedisServiceTokenCache(JedisPool pool) {
        if (pool == null) {
            throw new IllegalArgumentException("JedisPool is required");
        }
        this.pool = pool;
    }

    public static String serviceTokenKey(String masterIp) {
        return requireMasterIp(masterIp) + "ServiceToken";
    }

    public static String apiServerUrlKey(String masterIp) {
        return requireMasterIp(masterIp) + "ApiServerUrl";
    }

    private static String metadataKey(String masterIp) {
        return requireMasterIp(masterIp) + "ServiceTokenMetadata";
    }

    private static String requireMasterIp(String masterIp) {
        if (masterIp == null || masterIp.trim().isEmpty()) {
            throw new IllegalArgumentException("master IP is required");
        }
        return masterIp.trim();
    }

    MasterInfo load(String masterIp, String context) {
        List<String> values;
        try (Jedis jedis = pool.getResource()) {
            values = jedis.mget(serviceTokenKey(masterIp), apiServerUrlKey(masterIp), metadataKey(masterIp));
        } catch (JedisException e) {
            throw new K8sToolsException("读取 Redis 集群凭据失败", e);
        }
        if (values.get(0) == null || values.get(0).trim().isEmpty()
                || !ServiceTokenFetcher.isValidApiServerUrl(values.get(1)) || values.get(2) == null) {
            return null;
        }
        try {
            JsonObject metadata = JsonParser.parseString(values.get(2)).getAsJsonObject();
            if (metadata.get("version").getAsInt() != 1
                    || !context.equals(metadata.get("context").getAsString())) {
                return null;
            }
            String ca = metadata.has("caCertPem") && !metadata.get("caCertPem").isJsonNull()
                    ? metadata.get("caCertPem").getAsString() : null;
            return new MasterInfo(values.get(1), values.get(0), ca,
                    metadata.get("serviceAccount").getAsString(),
                    metadata.get("serviceAccountNamespace").getAsString());
        } catch (RuntimeException e) {
            // 不完整或旧格式元数据视为未命中，不输出可能含凭据的原文。
            return null;
        }
    }

    void save(String masterIp, String context, MasterInfo info) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("version", 1);
        metadata.addProperty("context", context);
        metadata.addProperty("serviceAccount", info.getServiceAccount());
        metadata.addProperty("serviceAccountNamespace", info.getServiceAccountNamespace());
        metadata.addProperty("caCertPem", info.getCaCertPem());
        try (Jedis jedis = pool.getResource()) {
            // 一个 MSET 原子更新，避免读到新 token 与旧地址的组合；覆盖时同时清除旧 TTL。
            jedis.mset(serviceTokenKey(masterIp), info.getToken(), apiServerUrlKey(masterIp),
                    info.getApiServerUrl(), metadataKey(masterIp), metadata.toString());
        } catch (JedisException e) {
            throw new K8sToolsException("写入 Redis 集群凭据失败", e);
        }
    }

    /** 一次删除 token、API 地址及关联元数据；不修改 Kubernetes 资源。 */
    public void invalidate(String masterIp) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(serviceTokenKey(masterIp), apiServerUrlKey(masterIp), metadataKey(masterIp));
        } catch (JedisException e) {
            throw new K8sToolsException("删除 Redis 集群凭据失败", e);
        }
    }
}
