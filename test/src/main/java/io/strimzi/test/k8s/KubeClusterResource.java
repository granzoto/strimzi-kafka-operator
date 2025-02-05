/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.k8s;

import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.cluster.KubeCluster;
import io.strimzi.test.k8s.cmdClient.KubeCmdClient;
import io.strimzi.test.k8s.exceptions.NoClusterException;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A Junit resource which discovers the running cluster and provides an appropriate KubeClient for it,
 * for use with {@code @BeforeAll} (or {@code BeforeEach}.
 * For example:
 * <pre><code>
 *     public static KubeClusterResource testCluster = new KubeClusterResources();
 *
 *     &#64;BeforeEach
 *     void before() {
 *         testCluster.before();
 *     }
 * </code></pre>
 */
public class KubeClusterResource {

    private static final Logger LOGGER = LogManager.getLogger(KubeClusterResource.class);

    private static final String CO_INSTALL_DIR = "../install/cluster-operator";

    private KubeCluster kubeCluster;
    private KubeCmdClient cmdClient;
    private boolean bootstrap;
    private KubeClient client;
    private HelmClient helmClient;
    private static KubeClusterResource cluster;

    private String namespace;
    private String testNamespace;

    protected List<String> bindingsNamespaces = new ArrayList<>();
    private List<String> deploymentNamespaces = new ArrayList<>();
    private List<String> deploymentResources = new ArrayList<>();
    private Stack<String> clusterOperatorConfigs = new Stack<>();

    protected String testClass;
    protected String testName;

    public static synchronized KubeClusterResource getInstance() {
        if (cluster == null) {
            try {
                cluster = new KubeClusterResource();
                initNamespaces();
                LOGGER.info("Cluster default namespace is {}", cluster.getNamespace());
                LOGGER.info("Cluster command line client default namespace is {}", cluster.getTestNamespace());
            } catch (RuntimeException e) {
                Assumptions.assumeTrue(false, e.getMessage());
            }
        }
        return cluster;
    }

    private KubeClusterResource() {
        this.bootstrap = true;
    }

    private static void initNamespaces() {
        cluster.setDefaultNamespace(cmdKubeClient().defaultNamespace());
        cluster.setTestNamespace(cmdKubeClient().defaultNamespace());
    }

    /**
     * Perform application of ServiceAccount, Roles and CRDs needed for proper cluster operator deployment.
     * Configuration files are loaded from install/cluster-operator directory.
     */
    public void applyClusterOperatorInstallFiles() {
        TimeMeasuringSystem.setTestName(testClass, testClass);
        TimeMeasuringSystem.startOperation(Operation.CO_CREATION);
        Map<File, String> operatorFiles = Arrays.stream(new File(CO_INSTALL_DIR).listFiles()).sorted().filter(file ->
                !file.getName().matches(".*(Binding|Deployment)-.*")
        ).collect(Collectors.toMap(file -> file, f -> TestUtils.getContent(f, TestUtils::toYamlString), (x, y) -> x, LinkedHashMap::new));
        for (Map.Entry<File, String> entry : operatorFiles.entrySet()) {
            LOGGER.info("Applying configuration file: {}", entry.getKey());
            clusterOperatorConfigs.push(entry.getKey().getPath());
            cmdKubeClient().clientWithAdmin().namespace(getNamespace()).apply(entry.getKey().getPath());
        }
        TimeMeasuringSystem.stopOperation(Operation.CO_CREATION);
    }

    public void setTestNamespace(String testNamespace) {
        this.testNamespace = testNamespace;
    }

    public void setDefaultNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Sets the namespace value for Kubernetes clients
     * @param futureNamespace Namespace which should be used in Kubernetes clients
     * @return Previous namespace which was used in Kubernetes clients
     */
    public String setNamespace(String futureNamespace) {
        String previousNamespace = namespace;
        LOGGER.info("Changing to {} namespace", futureNamespace);
        namespace = futureNamespace;
        return previousNamespace;
    }

    public List<String> getBindingsNamespaces() {
        return bindingsNamespaces;
    }

    /**
     * Gets namespace which is used in Kubernetes clients at the moment
     * @return Used namespace
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Provides appropriate CMD client for running cluster
     * @return CMD client
     */
    public static KubeCmdClient<?> cmdKubeClient() {
        return cluster.cmdClient().namespace(cluster.getNamespace());
    }

    /**
     * Provides appropriate CMD client with expected namespace for running cluster
     * @param inNamespace Namespace will be used as a current namespace for client
     * @return CMD client with expected namespace in configuration
     */
    public static KubeCmdClient<?> cmdKubeClient(String inNamespace) {
        return cluster.cmdClient().namespace(inNamespace);
    }

    /**
     * Provides appropriate Kubernetes client for running cluster
     * @return Kubernetes client
     */
    public static KubeClient kubeClient() {
        return cluster.client().namespace(cluster.getNamespace());
    }

    /**
     * Provides appropriate Kubernetes client with expected namespace for running cluster
     * @param inNamespace Namespace will be used as a current namespace for client
     * @return Kubernetes client with expected namespace in configuration
     */
    public static KubeClient kubeClient(String inNamespace) {
        return cluster.client().namespace(inNamespace);
    }

    /**
     * Delete ServiceAccount, Roles and CRDs from kubernetes cluster.
     */
    public void deleteClusterOperatorInstallFiles() {
        TimeMeasuringSystem.setTestName(testClass, testName);
        TimeMeasuringSystem.startOperation(Operation.CO_DELETION);

        while (!clusterOperatorConfigs.empty()) {
            String clusterOperatorConfig = clusterOperatorConfigs.pop();
            LOGGER.info("Deleting configuration file: {}", clusterOperatorConfig);
            cmdKubeClient().clientWithAdmin().namespace(getNamespace()).delete(clusterOperatorConfig);
        }
        TimeMeasuringSystem.stopOperation(Operation.CO_DELETION);
    }

    /**
     * Create namespaces for test resources.
     * @param useNamespace namespace which will be used as default by kubernetes client
     * @param namespaces list of namespaces which will be created
     */
    public void createNamespaces(String useNamespace, List<String> namespaces) {
        bindingsNamespaces = namespaces;
        for (String namespace: namespaces) {

            if (kubeClient().getNamespace(namespace) != null && System.getenv("SKIP_TEARDOWN") == null) {
                LOGGER.warn("Namespace {} is already created, going to delete it", namespace);
                kubeClient().deleteNamespace(namespace);
                cmdKubeClient().waitForResourceDeletion("Namespace", namespace);
            }

            LOGGER.info("Creating namespace: {}", namespace);
            deploymentNamespaces.add(namespace);
            kubeClient().createNamespace(namespace);
            cmdKubeClient().waitForResourceCreation("Namespace", namespace);
        }
        testNamespace = useNamespace;
        LOGGER.info("Using namespace {}", useNamespace);
        cluster.setNamespace(useNamespace);
    }

    /**
     * Create namespace for test resources. Deletion is up to caller and can be managed
     * by calling {@link #deleteNamespaces()}
     * @param useNamespace namespace which will be created and used as default by kubernetes client
     */
    public void createNamespace(String useNamespace) {
        createNamespaces(useNamespace, Collections.singletonList(useNamespace));
    }

    /**
     * Delete all created namespaces. Namespaces are deleted in the reverse order than they were created.
     */
    public void deleteNamespaces() {
        Collections.reverse(deploymentNamespaces);
        for (String namespace: deploymentNamespaces) {
            LOGGER.info("Deleting namespace: {}", namespace);
            kubeClient().deleteNamespace(namespace);
            cmdKubeClient().waitForResourceDeletion("Namespace", namespace);
        }
        deploymentNamespaces.clear();
        LOGGER.info("Using namespace {}", testNamespace);
        setNamespace(testNamespace);
    }


    /**
     * Apply custom resources for CO such as templates. Deletion is up to caller and can be managed
     * by calling {@link #deleteCustomResources()}
     * @param resources array of paths to yaml files with resources specifications
     */
    public void createCustomResources(String... resources) {
        for (String resource : resources) {
            LOGGER.info("Creating resources {} in namespace {}", resource, getNamespace());
            deploymentResources.add(resource);
            cmdKubeClient().clientWithAdmin().namespace(getNamespace()).create(resource);
        }
    }

    /**
     * Delete custom resources such as templates. Resources are deleted in the reverse order than they were created.
     */
    public void deleteCustomResources() {
        Collections.reverse(deploymentResources);
        for (String resource : deploymentResources) {
            LOGGER.info("Deleting resources {}", resource);
            cmdKubeClient().delete(resource);
        }
        deploymentResources.clear();
    }

    /**
     * Delete custom resources such as templates. Resources are deleted in the reverse order than they were created.
     */
    public void deleteCustomResources(String... resources) {
        for (String resource : resources) {
            LOGGER.info("Deleting resources {}", resource);
            cmdKubeClient().delete(resource);
            deploymentResources.remove(resource);
        }
    }

    /** Gets the namespace in use */
    public String defaultNamespace() {
        return cmdClient().defaultNamespace();
    }

    public KubeCmdClient cmdClient() {
        if (cmdClient == null) {
            cmdClient = cluster().defaultCmdClient();
        }
        return cmdClient;
    }

    public KubeClient client() {
        if (client == null) {
            this.client = cluster().defaultClient();
        }
        return client;
    }

    public HelmClient helmClient() {
        if (helmClient == null) {
            this.helmClient = HelmClient.findClient(cmdClient());
        }
        return helmClient;
    }

    public KubeCluster cluster() {
        if (kubeCluster == null) {
            try {
                kubeCluster = KubeCluster.bootstrap();
            } catch (NoClusterException e) {
                assumeTrue(false, e.getMessage());
            }
        }
        return kubeCluster;
    }

    public void before() {
        if (bootstrap) {
            if (kubeCluster == null) {
                try {
                    kubeCluster = KubeCluster.bootstrap();
                } catch (NoClusterException e) {
                    assumeTrue(false, e.getMessage());
                }
            }
            if (cmdClient == null) {
                cmdClient = kubeCluster.defaultCmdClient();
            }
            if (client == null) {
                this.client = kubeCluster.defaultClient();
            }
        }
    }

    public String getTestNamespace() {
        return testNamespace;
    }
}
