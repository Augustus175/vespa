// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.application;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.Node;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ServiceConvergence;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneId;

import java.util.Arrays;
import java.util.List;

/**
 * This represents a system-level application in hosted Vespa. E.g. the zone-application.
 *
 * @author mpolden
 */
public enum SystemApplication {

    // Note that the enum declaration order decides the upgrade order
    configServerHost(ApplicationId.from("hosted-vespa", "configserver-host", "default"), NodeType.confighost),
    proxyHost(ApplicationId.from("hosted-vespa", "proxy-host", "default"), NodeType.proxyhost),
    configServer(ApplicationId.from("hosted-vespa", "zone-config-servers", "default"), NodeType.config),
    zone(ApplicationId.from("hosted-vespa", "routing", "default"), NodeType.proxy,
         configServerHost, proxyHost, configServer);

    private final ApplicationId id;
    private final NodeType nodeType;
    private final List<SystemApplication> dependencies;

    SystemApplication(ApplicationId id, NodeType nodeType, SystemApplication... dependencies) {
        this.id = id;
        this.nodeType = nodeType;
        this.dependencies = Arrays.asList(dependencies);
    }

    public ApplicationId id() {
        return id;
    }

    /** The type of nodes that will be allocated to this */
    public NodeType nodeType() {
        return nodeType;
    }

    /** Returns the system applications that should upgrade before this */
    public List<SystemApplication> dependencies() { return dependencies; }

    /** Returns whether this system application has an application package */
    public boolean hasApplicationPackage() {
        return nodeType == NodeType.proxy;
    }

    /** Returns whether config for this application has converged in given zone */
    public boolean configConvergedIn(ZoneId zone, Controller controller) {
        if (!hasApplicationPackage()) {
            return true;
        }
        // TODO: Remove this hack once Docker hosts are removed from zone-application.
        if (isAws(zone.region())) {
            return true; // Skip checking config convergence on AWS as Docker hosts do not have cloud config
        }
        return controller.configServer().serviceConvergence(new DeploymentId(id(), zone))
                         .map(ServiceConvergence::converged)
                         .orElse(false);
    }

    /** All known system applications */
    public static List<SystemApplication> all() {
        return Arrays.asList(values());
    }

    /** Node states to consider when upgrading */
    public static List<Node.State> activeStates() {
        return Arrays.asList(Node.State.active, Node.State.reserved);
    }

    @Override
    public String toString() {
        return String.format("system application %s of type %s", id, nodeType);
    }

    private static boolean isAws(RegionName region) {
        return region.value().startsWith("cd-aws-") || region.value().startsWith("aws-");
    }

}
