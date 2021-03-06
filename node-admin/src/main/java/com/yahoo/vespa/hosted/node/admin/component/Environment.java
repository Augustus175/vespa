// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.component;

import com.google.common.base.Strings;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.athenz.utils.AthenzIdentities;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.hosted.dockerapi.ContainerName;
import com.yahoo.vespa.hosted.node.admin.config.ConfigServerConfig;
import com.yahoo.vespa.hosted.node.admin.util.InetAddressResolver;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

/**
 * Various utilities for getting values from node-admin's environment. Immutable.
 *
 * @author Øyvind Bakksjø
 * @author hmusum
 */
public class Environment {
    private static final DateFormat filenameFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    public static final String APPLICATION_STORAGE_CLEANUP_PATH_PREFIX = "cleanup_";

    private static final String ENVIRONMENT = "ENVIRONMENT";
    private static final String REGION = "REGION";
    private static final String SYSTEM = "SYSTEM";
    private static final String CLOUD = "CLOUD";
    private static final String LOGSTASH_NODES = "LOGSTASH_NODES";
    private static final String COREDUMP_FEED_ENDPOINT = "COREDUMP_FEED_ENDPOINT";
    private static final String CERTIFICATE_DNS_SUFFIX = "CERTIFICATE_DNS_SUFFIX";
    private static final String ZTS_URI = "ZTS_URL";
    private static final String NODE_ATHENZ_IDENTITY = "NODE_ATHENZ_IDENTITY";
    private static final String ENABLE_NODE_AGENT_CERT = "ENABLE_NODE_AGENT_CERT";
    private static final String TRUST_STORE_PATH = "TRUST_STORE_PATH";

    private final ConfigServerInfo configServerInfo;
    private final String environment;
    private final String region;
    private final String system;
    private final String parentHostHostname;
    private final InetAddressResolver inetAddressResolver;
    private final PathResolver pathResolver;
    private final List<String> logstashNodes;
    private final Optional<String> coredumpFeedEndpoint;
    private final NodeType nodeType;
    private final String cloud;
    private final ContainerEnvironmentResolver containerEnvironmentResolver;
    private final String certificateDnsSuffix;
    private final URI ztsUri;
    private final AthenzService nodeAthenzIdentity;
    private final boolean nodeAgentCertEnabled;
    private final Path trustStorePath;

    static {
        filenameFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public Environment(ConfigServerConfig configServerConfig) {
        this(configServerConfig,
             Paths.get(getEnvironmentVariable(TRUST_STORE_PATH)),
             getEnvironmentVariable(ENVIRONMENT),
             getEnvironmentVariable(REGION),
             getEnvironmentVariable(SYSTEM),
             Defaults.getDefaults().vespaHostname(),
             new InetAddressResolver(),
             new PathResolver(),
             getLogstashNodesFromEnvironment(),
             Optional.of(getEnvironmentVariable(COREDUMP_FEED_ENDPOINT)),
             NodeType.host,
             getEnvironmentVariable(CLOUD),
             new DefaultContainerEnvironmentResolver(),
             getEnvironmentVariable(CERTIFICATE_DNS_SUFFIX),
             URI.create(getEnvironmentVariable(ZTS_URI)),
             (AthenzService)AthenzIdentities.from(getEnvironmentVariable(NODE_ATHENZ_IDENTITY)),
             Boolean.valueOf(getEnvironmentVariable(ENABLE_NODE_AGENT_CERT)));
    }

    private Environment(ConfigServerConfig configServerConfig,
                        Path trustStorePath,
                        String environment,
                        String region,
                        String system,
                        String parentHostHostname,
                        InetAddressResolver inetAddressResolver,
                        PathResolver pathResolver,
                        List<String> logstashNodes,
                        Optional<String> coreDumpFeedEndpoint,
                        NodeType nodeType,
                        String cloud,
                        ContainerEnvironmentResolver containerEnvironmentResolver,
                        String certificateDnsSuffix,
                        URI ztsUri,
                        AthenzService nodeAthenzIdentity,
                        boolean nodeAgentCertEnabled) {
        Objects.requireNonNull(configServerConfig, "configServerConfig cannot be null");
        Objects.requireNonNull(environment, "environment cannot be null");
        Objects.requireNonNull(region, "region cannot be null");
        Objects.requireNonNull(system, "system cannot be null");
        Objects.requireNonNull(cloud, "cloud cannot be null");

        this.configServerInfo = new ConfigServerInfo(configServerConfig);
        this.environment = environment;
        this.region = region;
        this.system = system;
        this.parentHostHostname = parentHostHostname;
        this.inetAddressResolver = inetAddressResolver;
        this.pathResolver = pathResolver;
        this.logstashNodes = logstashNodes;
        this.coredumpFeedEndpoint = coreDumpFeedEndpoint;
        this.nodeType = nodeType;
        this.cloud = cloud;
        this.containerEnvironmentResolver = containerEnvironmentResolver;
        this.certificateDnsSuffix = certificateDnsSuffix;
        this.ztsUri = ztsUri;
        this.nodeAthenzIdentity = nodeAthenzIdentity;
        this.nodeAgentCertEnabled = nodeAgentCertEnabled;
        this.trustStorePath = trustStorePath;
    }

    public List<String> getConfigServerHostNames() { return configServerInfo.getConfigServerHostNames(); }

    public String getEnvironment() { return environment; }

    public String getRegion() {
        return region;
    }

    public String getSystem() {
        return system;
    }

    public String getParentHostHostname() {
        return parentHostHostname;
    }

    private static String getEnvironmentVariable(String name) {
        final String value = System.getenv(name);
        if (Strings.isNullOrEmpty(value)) {
            throw new IllegalStateException(String.format("Environment variable %s not set", name));
        }
        return value;
    }

    public String getZone() {
        return getEnvironment() + "." + getRegion();
    }

    private static List<String> getLogstashNodesFromEnvironment() {
        String logstashNodes = System.getenv(LOGSTASH_NODES);
        if (Strings.isNullOrEmpty(logstashNodes)) {
            return Collections.emptyList();
        }
        return Arrays.asList(logstashNodes.split("[,\\s]+"));
    }

    public InetAddress getInetAddressForHost(String hostname) throws UnknownHostException {
        return inetAddressResolver.getInetAddressForHost(hostname);
    }

    public PathResolver getPathResolver() {
        return pathResolver;
    }

    public Optional<String> getCoredumpFeedEndpoint() {
        return coredumpFeedEndpoint;
    }

    /**
     * Absolute path in node admin to directory with processed and reported core dumps
     */
    public Path pathInNodeAdminToDoneCoredumps() {
        return pathResolver.getApplicationStoragePathForNodeAdmin().resolve("processed-coredumps");
    }

    /**
     * Absolute path in node admin container to the node cleanup directory.
     */
    public Path pathInNodeAdminToNodeCleanup(ContainerName containerName) {
        return pathResolver.getApplicationStoragePathForNodeAdmin()
                .resolve(APPLICATION_STORAGE_CLEANUP_PATH_PREFIX + containerName.asString() +
                        "_" + filenameFormatter.format(Date.from(Instant.now())));
    }

    /**
     * Translates an absolute path in node agent container to an absolute path in node admin container.
     * @param containerName name of the node agent container
     * @param pathInNode absolute path in that container
     * @return the absolute path in node admin container pointing at the same inode
     */
    public Path pathInNodeAdminFromPathInNode(ContainerName containerName, Path pathInNode) {
        if (! pathInNode.isAbsolute()) {
            throw new IllegalArgumentException("The specified path in node was not absolute: " + pathInNode);
        }

        return pathResolver.getApplicationStoragePathForNodeAdmin()
                .resolve(containerName.asString())
                .resolve(PathResolver.ROOT.relativize(pathInNode));
    }

    /**
     * Translates an absolute path in node agent container to an absolute path in host.
     * @param containerName name of the node agent container
     * @param pathInNode absolute path in that container
     * @return the absolute path in host pointing at the same inode
     */
    public Path pathInHostFromPathInNode(ContainerName containerName, Path pathInNode) {
        if (! pathInNode.isAbsolute()) {
            throw new IllegalArgumentException("The specified path in node was not absolute: " + pathInNode);
        }

        return pathResolver.getApplicationStoragePathForHost()
                .resolve(containerName.asString())
                .resolve(PathResolver.ROOT.relativize(pathInNode));
    }

    public Path pathInNodeUnderVespaHome(String relativePath) {
        return pathResolver.getVespaHomePathForContainer()
                .resolve(relativePath);
    }

    public List<String> getLogstashNodes() {
        return logstashNodes;
    }

    public NodeType getNodeType() { return nodeType; }

    public String getCloud() { return cloud; }

    public ContainerEnvironmentResolver getContainerEnvironmentResolver() {
        return containerEnvironmentResolver;
    }

    public Path getTrustStorePath() {
        return trustStorePath;
    }

    public AthenzService getConfigserverAthenzIdentity() {
        return configServerInfo.getConfigServerIdentity();
    }

    public AthenzService getNodeAthenzIdentity() {
        return nodeAthenzIdentity;
    }

    public String getCertificateDnsSuffix() {
        return certificateDnsSuffix;
    }

    public URI getZtsUri() {
        return ztsUri;
    }

    public URI getConfigserverLoadBalancerEndpoint() {
        return configServerInfo.getLoadBalancerEndpoint();
    }

    public boolean isNodeAgentCertEnabled() {
        return nodeAgentCertEnabled;
    }

    public static class Builder {
        private ConfigServerConfig configServerConfig;
        private String environment;
        private String region;
        private String system;
        private String parentHostHostname;
        private InetAddressResolver inetAddressResolver;
        private PathResolver pathResolver;
        private List<String> logstashNodes = Collections.emptyList();
        private Optional<String> coredumpFeedEndpoint = Optional.empty();
        private NodeType nodeType = NodeType.tenant;
        private String cloud;
        private ContainerEnvironmentResolver containerEnvironmentResolver;
        private String certificateDnsSuffix;
        private URI ztsUri;
        private AthenzService nodeAthenzIdentity;
        private boolean nodeAgentCertEnabled;
        private Path trustStorePath;

        public Builder configServerConfig(ConfigServerConfig configServerConfig) {
            this.configServerConfig = configServerConfig;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder parentHostHostname(String parentHostHostname) {
            this.parentHostHostname = parentHostHostname;
            return this;
        }

        public Builder inetAddressResolver(InetAddressResolver inetAddressResolver) {
            this.inetAddressResolver = inetAddressResolver;
            return this;
        }

        public Builder pathResolver(PathResolver pathResolver) {
            this.pathResolver = pathResolver;
            return this;
        }

        public Builder containerEnvironmentResolver(ContainerEnvironmentResolver containerEnvironmentResolver) {
            this.containerEnvironmentResolver = containerEnvironmentResolver;
            return this;
        }

        public Builder logstashNodes(List<String> hosts) {
            this.logstashNodes = hosts;
            return this;
        }

        public Builder coredumpFeedEndpoint(String coredumpFeedEndpoint) {
            this.coredumpFeedEndpoint = Optional.of(coredumpFeedEndpoint);
            return this;
        }

        public Builder nodeType(NodeType nodeType) {
            this.nodeType = nodeType;
            return this;
        }

        public Builder cloud(String cloud) {
            this.cloud = cloud;
            return this;
        }

        public Builder certificateDnsSuffix(String certificateDnsSuffix) {
            this.certificateDnsSuffix = certificateDnsSuffix;
            return this;
        }

        public Builder ztsUri(URI ztsUri) {
            this.ztsUri = ztsUri;
            return this;
        }

        public Builder nodeAthenzIdentity(AthenzService nodeAthenzIdentity) {
            this.nodeAthenzIdentity = nodeAthenzIdentity;
            return this;
        }

        public Builder enableNodeAgentCert(boolean nodeAgentCertEnabled) {
            this.nodeAgentCertEnabled = nodeAgentCertEnabled;
            return this;
        }

        public Builder trustStorePath(Path trustStorePath) {
            this.trustStorePath = trustStorePath;
            return this;
        }

        public Environment build() {
            return new Environment(configServerConfig,
                                   trustStorePath,
                                   environment,
                                   region,
                                   system,
                                   parentHostHostname,
                                   Optional.ofNullable(inetAddressResolver).orElseGet(InetAddressResolver::new),
                                   Optional.ofNullable(pathResolver).orElseGet(PathResolver::new),
                                   logstashNodes,
                                   coredumpFeedEndpoint,
                                   nodeType,
                                   cloud,
                                   Optional.ofNullable(containerEnvironmentResolver).orElseGet(DefaultContainerEnvironmentResolver::new),
                                   certificateDnsSuffix,
                                   ztsUri,
                                   nodeAthenzIdentity,
                                   nodeAgentCertEnabled);
        }
    }
}
