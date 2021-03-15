package tech.stackable.t2.api.cluster.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import tech.stackable.t2.ansible.AnsibleResult;
import tech.stackable.t2.ansible.AnsibleService;
import tech.stackable.t2.api.cluster.domain.Cluster;
import tech.stackable.t2.api.cluster.domain.Status;
import tech.stackable.t2.dns.DnsService;
import tech.stackable.t2.templates.TemplateService;
import tech.stackable.t2.terraform.TerraformResult;
import tech.stackable.t2.terraform.TerraformService;

/**
 * Manages clusters provisioned with Terraform and Ansible
 */
@Repository
@ConditionalOnProperty(name = "t2.feature.provision-real-clusters", havingValue = "true")
public class TerraformAnsibleClusterService implements ClusterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformAnsibleClusterService.class);

    private static final Duration CLEANUP_INACTIVITY_THRESHOLD = Duration.ofDays(1);

    @Autowired
    @Qualifier("workspaceDirectory")
    private Path workspaceDirectory;

    @Autowired
    @Qualifier("credentials")
    private Properties credentials;

    @Autowired
    private DnsService dnsService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private TerraformService terraformService;

    @Autowired
    private AnsibleService ansibleService;

    private int provisionClusterLimit = -1;

    /**
     * cluster metadata per cluster (UUID)
     */
    private Map<UUID, Cluster> clusters = new HashMap<>();

    public TerraformAnsibleClusterService(@Value("${t2.feature.provision-cluster-limit}") int provisionClusterLimit) {
        this.provisionClusterLimit = provisionClusterLimit;
        LOGGER.info("Created TerraformAnsibleClusterService, cluster count limit: {}", this.provisionClusterLimit);
    }

    @Override
    public Collection<Cluster> getAllClusters() {
        return this.clusters.values();
    }

    @Override
    public Cluster getCluster(UUID id) {
        return this.clusters.get(id);
    }

    @Override
    public Cluster createCluster(Map<String, Object> clusterDefinition) {
        synchronized (this.clusters) {

            // TODO count only active clusters for limit
            if (clusters.size() >= this.provisionClusterLimit) {
                throw new ClusterLimitReachedException();
            }
            Cluster cluster = new Cluster();
            cluster.setStatus(Status.CREATION_STARTED);

            Path workingDirectory = this.templateService.createWorkingDirectory(cluster, clusterDefinition);
            cluster.setStatus(Status.WORKING_DIR_CREATED);
            clusters.put(cluster.getId(), cluster);

            new Thread(() -> {

                TerraformResult terraformResult = null;

                cluster.setStatus(Status.TERRAFORM_INIT);
                terraformResult = this.terraformService.init(workingDirectory, datacenterName(cluster.getId()));
                if (terraformResult == TerraformResult.ERROR) {
                    cluster.setStatus(Status.TERRAFORM_INIT_FAILED);
                    return;
                }

                cluster.setStatus(Status.TERRAFORM_PLAN);
                terraformResult = this.terraformService.plan(workingDirectory, datacenterName(cluster.getId()));
                if (terraformResult == TerraformResult.ERROR) {
                    cluster.setStatus(Status.TERRAFORM_PLAN_FAILED);
                    return;
                }

                cluster.setStatus(Status.TERRAFORM_APPLY);
                terraformResult = this.terraformService.apply(workingDirectory, datacenterName(cluster.getId()));
                if (terraformResult == TerraformResult.ERROR) {
                    cluster.setStatus(Status.TERRAFORM_APPLY_FAILED);
                    return;
                }

                cluster.setIpV4Address(this.terraformService.getIpV4(workingDirectory));

                cluster.setStatus(Status.DNS_WRITE_RECORD);
                String hostname = this.dnsService.addSubdomain(cluster.getShortId(), cluster.getIpV4Address());
                if (hostname == null) {
                    cluster.setStatus(Status.DNS_WRITE_RECORD_FAILED);
                    return;
                }

                cluster.setHostname(hostname);

                cluster.setStatus(Status.ANSIBLE_PROVISIONING);

                // TODO use kind of a retry mechanism instead of waiting stupidly
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                AnsibleResult ansibleResult = this.ansibleService.run(workingDirectory);
                if (ansibleResult == AnsibleResult.ERROR) {
                    cluster.setStatus(Status.ANSIBLE_FAILED);
                    return;
                }

                cluster.setStatus(Status.RUNNING);

            }).start();

            return cluster;
        }
    }

    @Override
    public Cluster deleteCluster(UUID id) {
        synchronized (this.clusters) {
            Cluster cluster = this.clusters.get(id);
            if (cluster == null) {
                return null;
            }
            cluster.setStatus(Status.DELETION_STARTED);

            new Thread(() -> {

                cluster.setStatus(Status.DNS_DELETE_RECORD);
                boolean dnsRemovalSucceded = this.dnsService.removeSubdomain(cluster.getShortId());
                if (!dnsRemovalSucceded) {
                    cluster.setStatus(Status.DNS_DELETE_RECORD_FAILED);
                    return;
                }

                Path terraformFolder = this.templateService.getWorkingDirectory(cluster);

                cluster.setStatus(Status.TERRAFORM_DESTROY);
                TerraformResult terraformResult = this.terraformService.destroy(terraformFolder, datacenterName(cluster.getId()));
                if (terraformResult == TerraformResult.ERROR) {
                    cluster.setStatus(Status.TERRAFORM_DESTROY_FAILED);
                    return;
                }
                cluster.setIpV4Address(null);
                cluster.setStatus(Status.TERMINATED);
            }).start();

            return cluster;
        }
    }

    @Override
    public String getWireguardClientConfig(UUID id, int index) {
        Cluster cluster = this.clusters.get(id);
        if (cluster == null) {
            return null;
        }
        Path clusterBaseFolder = this.templateService.getWorkingDirectory(cluster);
        try {
            return FileUtils.readFileToString(clusterBaseFolder.resolve(MessageFormat.format("resources/wireguard-client-config/{0}/wg.conf", index)).toFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Wireguard client config could not be read", e);
            return null;
        }
    }

    @Override
    public String getClientScript(UUID id) {
        Cluster cluster = this.clusters.get(id);
        if (cluster == null) {
            return null;
        }
        Path clusterBaseFolder = this.templateService.getWorkingDirectory(cluster);
        try {
            return FileUtils.readFileToString(clusterBaseFolder.resolve("resources/stackable.sh").toFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Stackable client script could not be read", e);
            return null;
        }
    }

    @Override
    public String getLogs(UUID id) {
        Cluster cluster = this.clusters.get(id);
        if (cluster == null) {
            return "";
        }
        Path clusterBaseFolder = this.templateService.getWorkingDirectory(cluster);
        try {
            return FileUtils.readFileToString(clusterBaseFolder.resolve("cluster.log").toFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Wireguard client config could not be read", e);
            return "";
        }
    }
    
    /**
     * Cleans up list of clusters regularly.
     */
    @Scheduled(cron = "0 0 * * * *") // on the hour
    private void cleanup() {
        LOGGER.info("cleaning up clusters ...");
        List<UUID> clustersToDelete = this.clusters.values()
                .stream()
                .filter(TerraformAnsibleClusterService::readyForCleanup)
                .map(Cluster::getId)
                .collect(Collectors.toList());

        synchronized (clusters) {
            clustersToDelete.forEach(id -> {
                LOGGER.info("Cluster {} will be cleaned up.", id);
                this.clusters.remove(id);
            });
        }

        LOGGER.info("cleaned up {} clusters.", clustersToDelete.size());
    }

    /**
     * Decides if a given Cluster is ready to be cleaned up.
     * 
     * We assume that clusters that are not in state {@link Status#RUNNING} and
     * haven't changed their state for a day are ready to be removed.
     * 
     * @param cluster cluster to check
     * @return Is the given cluster ready to be cleaned up?
     */
    private static boolean readyForCleanup(Cluster cluster) {
        return !(cluster.getStatus() == Status.RUNNING)
                && Duration.between(cluster.getLastChangedAt(), LocalDateTime.now()).compareTo(CLEANUP_INACTIVITY_THRESHOLD) > 0;
    }

    /**
     * Datacenter name for the given cluster
     * 
     * @param clusterId Cluster ID
     * @return datacenter name for the given cluster
     */
    private String datacenterName(UUID clusterId) {
        return String.format("t2-%s", clusterId);
    }

}
