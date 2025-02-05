/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.strimzi.api.kafka.model.EntityOperatorJvmOptions;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaMirrorMakerResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.resources.crd.KafkaBridgeResource;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMakerResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(REGRESSION)
@TestMethodOrder(OrderAnnotation.class)
class LogSettingST extends AbstractST {
    static final String NAMESPACE = "log-setting-cluster-test";
    private static final Logger LOGGER = LogManager.getLogger(LogSettingST.class);
    private static final String KAFKA_MAP = String.format("%s-%s", CLUSTER_NAME, "kafka-config");
    private static final String ZOOKEEPER_MAP = String.format("%s-%s", CLUSTER_NAME, "zookeeper-config");
    private static final String TO_MAP = String.format("%s-%s", CLUSTER_NAME, "entity-topic-operator-config");
    private static final String UO_MAP = String.format("%s-%s", CLUSTER_NAME, "entity-user-operator-config");
    private static final String CONNECT_MAP = String.format("%s-%s", CLUSTER_NAME, "connect-config");
    private static final String MM_MAP = String.format("%s-%s", CLUSTER_NAME, "mirror-maker-config");
    private static final String BRIDGE_MAP = String.format("%s-%s", CLUSTER_NAME, "bridge-config");

    private static final String INFO = "INFO";
    private static final String ERROR = "ERROR";
    private static final String WARN = "WARN";
    private static final String TRACE = "TRACE";
    private static final String DEBUG = "DEBUG";
    private static final String FATAL = "FATAL";
    private static final String OFF = "OFF";

    private static final String GC_LOGGING_SET_NAME = "gc-set-logging";

    private static final Map<String, String> KAFKA_LOGGERS = new HashMap<String, String>() {
        {
            put("kafka.root.logger.level", INFO);
            put("test.kafka.logger.level", INFO);
            put("log4j.logger.org.I0Itec.zkclient.ZkClient", ERROR);
            put("log4j.logger.org.apache.zookeeper", WARN);
            put("log4j.logger.kafka", TRACE);
            put("log4j.logger.org.apache.kafka", DEBUG);
            put("log4j.logger.kafka.request.logger", FATAL);
            put("log4j.logger.kafka.network.Processor", OFF);
            put("log4j.logger.kafka.server.KafkaApis", INFO);
            put("log4j.logger.kafka.network.RequestChannel$", ERROR);
            put("log4j.logger.kafka.controller", WARN);
            put("log4j.logger.kafka.log.LogCleaner", TRACE);
            put("log4j.logger.state.change.logger", DEBUG);
            put("log4j.logger.kafka.authorizer.logger", FATAL);
        }
    };

    private static final Map<String, String> ZOOKEEPER_LOGGERS = new HashMap<String, String>() {
        {
            put("zookeeper.root.logger", OFF);
            put("test.zookeeper.logger.level", DEBUG);
        }
    };

    private static final Map<String, String> CONNECT_LOGGERS = new HashMap<String, String>() {
        {
            put("connect.root.logger.level", INFO);
            put("test.connect.logger.level", DEBUG);
            put("log4j.logger.org.I0Itec.zkclient", ERROR);
            put("log4j.logger.org.reflections", WARN);
        }
    };

    private static final Map<String, String> OPERATORS_LOGGERS = new HashMap<String, String>() {
        {
            put("rootLogger.level", DEBUG);
            put("test.operator.logger.level", DEBUG);
        }
    };

    private static final Map<String, String> MIRROR_MAKER_LOGGERS = new HashMap<String, String>() {
        {
            put("mirrormaker.root.logger", TRACE);
            put("test.mirrormaker.logger.level", TRACE);
        }
    };

    private static final Map<String, String> BRIDGE_LOGGERS = new HashMap<String, String>() {
        {
            put("log4j.logger.http.openapi.operation.createConsumer", INFO);
            put("log4j.logger.http.openapi.operation.deleteConsumer", DEBUG);
            put("log4j.logger.http.openapi.operation.subscribe", TRACE);
            put("log4j.logger.http.openapi.operation.unsubscribe", DEBUG);
            put("log4j.logger.http.openapi.operation.poll", INFO);
            put("log4j.logger.http.openapi.operation.assign", TRACE);
            put("log4j.logger.http.openapi.operation.commit", DEBUG);
            put("log4j.logger.http.openapi.operation.send", ERROR);
            put("log4j.logger.http.openapi.operation.sendToPartition", TRACE);
            put("log4j.logger.http.openapi.operation.seekToBeginning", DEBUG);
            put("log4j.logger.http.openapi.operation.seekToEnd", WARN);
            put("log4j.logger.http.openapi.operation.seek", INFO);
            put("log4j.logger.http.openapi.operation.healthy", ERROR);
            put("log4j.logger.http.openapi.operation.ready", WARN);
            put("log4j.logger.http.openapi.operation.openapi", TRACE);
            put("test.bridge.logger.level", ERROR);
        }
    };

    @Test
    @Order(1)
    void testLoggersKafka() {
        assertThat("Kafka's log level is set properly", checkLoggersLevel(KAFKA_LOGGERS, KAFKA_MAP), is(true));
    }

    @Test
    @Order(2)
    void testLoggersZookeeper() {
        assertThat("Zookeeper's log level is set properly", checkLoggersLevel(ZOOKEEPER_LOGGERS, ZOOKEEPER_MAP), is(true));
    }

    @Test
    @Order(3)
    void testLoggersTO() {
        assertThat("Topic operator's log level is set properly", checkLoggersLevel(OPERATORS_LOGGERS, TO_MAP), is(true));
    }

    @Test
    @Order(4)
    void testLoggersUO() {
        assertThat("User operator's log level is set properly", checkLoggersLevel(OPERATORS_LOGGERS, UO_MAP), is(true));
    }

    @Test
    @Order(5)
    void testLoggersKafkaConnect() {
        assertThat("Kafka connect's log level is set properly", checkLoggersLevel(CONNECT_LOGGERS, CONNECT_MAP), is(true));
    }

    @Test
    @Order(6)
    void testLoggersMirrorMaker() {
        assertThat("Mirror maker's log level is set properly", checkLoggersLevel(MIRROR_MAKER_LOGGERS, MM_MAP), is(true));
    }

    @Test
    @Order(7)
    void testLoggersBridge() {
        assertThat("Bridge's log level is set properly", checkLoggersLevel(BRIDGE_LOGGERS, BRIDGE_MAP), is(true));
    }

    @Test
    @Order(8)
    void testGcLoggingNonSetDisabled() {
        assertThat("Kafka GC logging is enabled", checkGcLoggingStatefulSets(KafkaResources.kafkaStatefulSetName(GC_LOGGING_SET_NAME)), is(false));
        assertThat("Zookeeper GC logging is enabled", checkGcLoggingStatefulSets(KafkaResources.zookeeperStatefulSetName(GC_LOGGING_SET_NAME)), is(false));

        assertThat("TO GC logging is enabled", checkGcLoggingDeployments(KafkaResources.entityOperatorDeploymentName(GC_LOGGING_SET_NAME), "topic-operator"), is(false));
        assertThat("UO GC logging is enabled", checkGcLoggingDeployments(KafkaResources.entityOperatorDeploymentName(GC_LOGGING_SET_NAME), "user-operator"), is(false));
    }

    @Test
    @Order(9)
    void testGcLoggingSetEnabled() {
        assertThat("Kafka GC logging is enabled", checkGcLoggingStatefulSets(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME)), is(true));
        assertThat("Zookeeper GC logging is enabled", checkGcLoggingStatefulSets(KafkaResources.zookeeperStatefulSetName(CLUSTER_NAME)), is(true));

        assertThat("TO GC logging is enabled", checkGcLoggingDeployments(KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME), "topic-operator"), is(true));
        assertThat("UO GC logging is enabled", checkGcLoggingDeployments(KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME), "user-operator"), is(true));

        assertThat("Connect GC logging is enabled", checkGcLoggingDeployments(KafkaConnectResources.deploymentName(CLUSTER_NAME)), is(true));
        assertThat("Mirror-maker GC logging is enabled", checkGcLoggingDeployments(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME)), is(true));
    }

    @Test
    @Order(10)
    void testGcLoggingSetDisabled() {
        String connectName = KafkaConnectResources.deploymentName(CLUSTER_NAME);
        String mmName = KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME);
        String eoName = KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME);
        String kafkaName = KafkaResources.kafkaStatefulSetName(CLUSTER_NAME);
        String zkName = KafkaResources.zookeeperStatefulSetName(CLUSTER_NAME);
        Map<String, String> connectPods = StUtils.depSnapshot(connectName);
        Map<String, String> mmPods = StUtils.depSnapshot(mmName);
        Map<String, String> eoPods = StUtils.depSnapshot(eoName);
        Map<String, String> kafkaPods = StUtils.ssSnapshot(kafkaName);
        Map<String, String> zkPods = StUtils.ssSnapshot(zkName);

        JvmOptions jvmOptions = new JvmOptions();
        jvmOptions.setGcLoggingEnabled(false);

        EntityOperatorJvmOptions entityOperatorJvmOptions = new EntityOperatorJvmOptions();
        entityOperatorJvmOptions.setGcLoggingEnabled(false);

        replaceKafkaResource(CLUSTER_NAME, k -> {
            k.getSpec().getKafka().setJvmOptions(jvmOptions);
            k.getSpec().getZookeeper().setJvmOptions(jvmOptions);
            k.getSpec().getEntityOperator().getTopicOperator().setJvmOptions(entityOperatorJvmOptions);
            k.getSpec().getEntityOperator().getUserOperator().setJvmOptions(entityOperatorJvmOptions);
        });

        StUtils.waitTillSsHasRolled(zkName, 3, zkPods);
        StUtils.waitTillSsHasRolled(kafkaName, 3, kafkaPods);
        StUtils.waitTillDepHasRolled(eoName, 1, eoPods);

        replaceKafkaConnectResource(CLUSTER_NAME, k -> k.getSpec().setJvmOptions(jvmOptions));
        StUtils.waitTillDepHasRolled(connectName, 1, connectPods);

        replaceMirrorMakerResource(CLUSTER_NAME, k -> k.getSpec().setJvmOptions(jvmOptions));
        StUtils.waitTillDepHasRolled(mmName, 1, mmPods);

        assertThat("Kafka GC logging is disabled", checkGcLoggingStatefulSets(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME)), is(false));
        assertThat("Zookeeper GC logging is disabled", checkGcLoggingStatefulSets(KafkaResources.zookeeperStatefulSetName(CLUSTER_NAME)), is(false));

        assertThat("TO GC logging is disabled", checkGcLoggingDeployments(KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME), "topic-operator"), is(false));
        assertThat("UO GC logging is disabled", checkGcLoggingDeployments(KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME), "user-operator"), is(false));

        assertThat("Connect GC logging is disabled", checkGcLoggingDeployments(KafkaConnectResources.deploymentName(CLUSTER_NAME)), is(false));
        assertThat("Mirror-maker GC logging is disabled", checkGcLoggingDeployments(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME)), is(false));
    }

    private boolean checkLoggersLevel(Map<String, String> loggers, String configMapName) {
        boolean result = false;
        String configMap = kubeClient().getConfigMap(configMapName).getData().get(configMapName.contains("operator") ? "log4j2.properties" : "log4j.properties");
        for (Map.Entry<String, String> entry : loggers.entrySet()) {
            LOGGER.info("Check log level setting for logger: {} Expected: {}", entry.getKey(), entry.getValue());
            String loggerConfig = String.format("%s=%s", entry.getKey(), entry.getValue());
            result = configMap.contains(loggerConfig);

            // Validation failed
            if (!result) {
                break;
            }
        }

        return result;
    }

    private Boolean checkGcLoggingDeployments(String deploymentName, String containerName) {
        LOGGER.info("Checking deployment: {}", deploymentName);
        List<Container> containers = kubeClient().getDeployment(deploymentName).getSpec().getTemplate().getSpec().getContainers();
        Container container = getContainerByName(containerName, containers);
        LOGGER.info("Checking container with name: {}", container.getName());
        return checkEnvVarValue(container);
    }

    private Boolean checkGcLoggingDeployments(String deploymentName) {
        LOGGER.info("Checking deployment: {}", deploymentName);
        Container container = kubeClient().getDeployment(deploymentName).getSpec().getTemplate().getSpec().getContainers().get(0);
        LOGGER.info("Checking container with name: {}", container.getName());
        return checkEnvVarValue(container);
    }

    private Boolean checkGcLoggingStatefulSets(String statefulSetName) {
        LOGGER.info("Checking stateful set: {}", statefulSetName);
        Container container = kubeClient().getStatefulSet(statefulSetName).getSpec().getTemplate().getSpec().getContainers().get(0);
        LOGGER.info("Checking container with name: {}", container.getName());
        return checkEnvVarValue(container);
    }

    private Container getContainerByName(String containerName, List<Container> containers) {
        return containers.stream().filter(c -> c.getName().equals(containerName)).findFirst().orElse(null);
    }

    private Boolean checkEnvVarValue(Container container) {
        assertThat("Container is null!", container, is(notNullValue()));

        List<EnvVar> loggingEnvVar = container.getEnv().stream().filter(envVar -> envVar.getName().contains("GC_LOG_ENABLED")).collect(Collectors.toList());
        LOGGER.info("{}={}", loggingEnvVar.get(0).getName(), loggingEnvVar.get(0).getValue());
        return loggingEnvVar.get(0).getValue().contains("true");
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        prepareEnvForOperator(NAMESPACE);

        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        KubernetesResource.clusterOperator(NAMESPACE).done();

        setOperationID(startDeploymentMeasuring());

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .withNewInlineLogging()
                        .withLoggers(KAFKA_LOGGERS)
                    .endInlineLogging()
                    .withNewJvmOptions()
                        .withGcLoggingEnabled(true)
                    .endJvmOptions()
                .endKafka()
                .editZookeeper()
                    .withNewInlineLogging()
                        .withLoggers(ZOOKEEPER_LOGGERS)
                    .endInlineLogging()
                    .withNewJvmOptions()
                        .withGcLoggingEnabled(true)
                    .endJvmOptions()
                .endZookeeper()
                .editEntityOperator()
                    .editOrNewUserOperator()
                        .withNewInlineLogging()
                            .withLoggers(OPERATORS_LOGGERS)
                        .endInlineLogging()
                        .withNewJvmOptions()
                            .withGcLoggingEnabled(true)
                        .endJvmOptions()
                    .endUserOperator()
                    .editOrNewTopicOperator()
                        .withNewInlineLogging()
                            .withLoggers(OPERATORS_LOGGERS)
                        .endInlineLogging()
                        .withNewJvmOptions()
                            .withGcLoggingEnabled(true)
                        .endJvmOptions()
                    .endTopicOperator()
                .endEntityOperator()
            .endSpec()
            .done();

        KafkaResource.kafkaEphemeral(GC_LOGGING_SET_NAME, 3, 1)
            .editSpec()
                .editKafka()
                    .withNewJvmOptions()
                    .endJvmOptions()
                .endKafka()
                .editZookeeper()
                    .withNewJvmOptions()
                    .endJvmOptions()
                .endZookeeper()
                .editEntityOperator()
                    .editTopicOperator()
                        .withNewJvmOptions()
                        .endJvmOptions()
                    .endTopicOperator()
                    .editUserOperator()
                        .withNewJvmOptions()
                        .endJvmOptions()
                    .endUserOperator()
                .endEntityOperator()
            .endSpec()
            .done();

        KafkaConnectResource.kafkaConnect(CLUSTER_NAME, 1)
            .editSpec()
                .withNewInlineLogging()
                    .withLoggers(CONNECT_LOGGERS)
                .endInlineLogging()
                .withNewJvmOptions()
                    .withGcLoggingEnabled(true)
                .endJvmOptions()
            .endSpec().done();

        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, CLUSTER_NAME, GC_LOGGING_SET_NAME, "my-group", 1, false)
            .editSpec()
                .withNewInlineLogging()
                  .withLoggers(MIRROR_MAKER_LOGGERS)
                .endInlineLogging()
                .withNewJvmOptions()
                    .withGcLoggingEnabled(true)
                .endJvmOptions()
            .endSpec()
            .done();

        KafkaBridgeResource.kafkaBridge(CLUSTER_NAME, KafkaResources.plainBootstrapAddress(CLUSTER_NAME), 1)
            .editSpec()
                .withNewInlineLogging()
                    .withLoggers(BRIDGE_LOGGERS)
                .endInlineLogging()
            .endSpec().done();
    }

    private String startDeploymentMeasuring() {
        TimeMeasuringSystem.setTestName(testClass, testClass);
        return TimeMeasuringSystem.startOperation(Operation.CLASS_EXECUTION);
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        LOGGER.info("Skip env recreation after failed tests!");
    }

    @Override
    protected void tearDownEnvironmentAfterAll() {
        teardownEnvForOperator();
    }
}
