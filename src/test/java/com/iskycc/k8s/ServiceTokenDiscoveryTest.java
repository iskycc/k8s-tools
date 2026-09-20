package com.iskycc.k8s;

import com.iskycc.k8s.ssh.ExecResult;
import com.iskycc.k8s.ssh.MasterInfo;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import com.iskycc.k8s.ssh.SshExecutor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ServiceTokenDiscoveryTest {
    @Test
    public void loopbackAndWildcardDiscoveryUseMasterAddressAndDiscoveredPort() {
        for (String host : new String[]{"localhost", "127.0.0.1", "0.0.0.0", "[::1]", "[::]"}) {
            DiscoveryExecutor ssh = executor("192.0.2.10", "https://" + host + ":7443", "");
            assertEquals("https://192.0.2.10:7443", fetch(ssh).getApiServerUrl());
            assertTrue(ssh.closed);
        }
        assertEquals("https://127.example.org:7443", fetch(executor("192.0.2.10",
                "https://127.example.org:7443", "")).getApiServerUrl());
    }

    @Test
    public void invalidDiscoveryFallsBackToAdminConfAndIpv6Default() {
        for (String invalid : new String[]{"invalid-output", "https://user:password@example.org",
                "https://example.org?token=secret", "https://example.org:99999", "https://example.org/api"}) {
            assertEquals("https://192.0.2.20:6443", fetch(executor("192.0.2.10", invalid,
                    "https://192.0.2.20:6443")).getApiServerUrl());
        }
        assertEquals("https://[2001:db8::10]:6443", fetch(executor("2001:db8::10", "", "")).getApiServerUrl());
    }

    @Test
    public void overrideIsValidatedBeforeSshAndRetainsExplicitLoopback() {
        DiscoveryExecutor ssh = executor("192.0.2.10", "", "");
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenFetcher(ssh.config,
                new ServiceTokenFetcher.Options().apiServerOverride("not-a-url"), ssh).fetch());
        assertFalse(ssh.connected);
        assertEquals("https://localhost:7443", new ServiceTokenFetcher(ssh.config,
                new ServiceTokenFetcher.Options().apiServerOverride("https://localhost:7443"), ssh)
                .fetch().getApiServerUrl());
        assertFalse(ssh.discovered);
    }

    private MasterInfo fetch(DiscoveryExecutor ssh) {
        return new ServiceTokenFetcher(ssh.config, new ServiceTokenFetcher.Options().fetchCaCert(false), ssh).fetch();
    }

    private DiscoveryExecutor executor(String host, String kubeconfig, String adminConfig) {
        return new DiscoveryExecutor(SshConfig.builder().host(host).password("test-password").build(),
                kubeconfig, adminConfig);
    }

    private static final class DiscoveryExecutor extends SshExecutor {
        private final SshConfig config;
        private final String kubeconfig;
        private final String adminConfig;
        private boolean connected;
        private boolean closed;
        private boolean discovered;

        private DiscoveryExecutor(SshConfig config, String kubeconfig, String adminConfig) {
            super(config);
            this.config = config;
            this.kubeconfig = kubeconfig;
            this.adminConfig = adminConfig;
        }

        @Override
        public void connect() { connected = true; }

        @Override
        public ExecResult exec(String command, int timeoutMs) {
            if (command.startsWith("kubectl config view")) {
                discovered = true;
                return new ExecResult(0, kubeconfig, "");
            }
            if (command.startsWith("awk ")) { return new ExecResult(0, adminConfig, ""); }
            if (command.contains("jsonpath={.secrets[0].name}")) { return new ExecResult(0, "existing-token", ""); }
            if (command.contains("jsonpath={.data.token}")) { return new ExecResult(0, "dGVzdC10b2tlbg==", ""); }
            return new ExecResult(0, "", "");
        }

        @Override
        public void close() { closed = true; }
    }
}
