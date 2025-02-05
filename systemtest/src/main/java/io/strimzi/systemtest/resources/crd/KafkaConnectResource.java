/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources.crd;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.strimzi.systemtest.resources.ResourceManager;

public class KafkaConnectResource {
    private static final Logger LOGGER = LogManager.getLogger(KafkaConnectResource.class);

    public static final String PATH_TO_KAFKA_CONNECT_CONFIG = "../examples/kafka-connect/kafka-connect.yaml";
    public static final String PATH_TO_KAFKA_CONNECT_METRICS_CONFIG = "../metrics/examples/kafka/kafka-connect-metrics.yaml";

    public static MixedOperation<KafkaConnect, KafkaConnectList, DoneableKafkaConnect, Resource<KafkaConnect, DoneableKafkaConnect>> kafkaConnectClient() {
        return Crds.kafkaConnectOperation(ResourceManager.kubeClient().getClient());
    }

    public static DoneableKafkaConnect kafkaConnect(String name, int kafkaConnectReplicas) {
        return kafkaConnect(name, name, kafkaConnectReplicas);
    }

    public static DoneableKafkaConnect kafkaConnect(String name, String clusterName, int kafkaConnectReplicas) {
        KafkaConnect kafkaConnect = getKafkaConnectFromYaml(PATH_TO_KAFKA_CONNECT_CONFIG);
        return deployKafkaConnect(defaultKafkaConnect(kafkaConnect, name, clusterName, kafkaConnectReplicas).build());
    }

    public static DoneableKafkaConnect kafkaConnectWithMetrics(String name, int kafkaConnectReplicas) {
        return kafkaConnectWithMetrics(name, name, kafkaConnectReplicas);
    }

    public static DoneableKafkaConnect kafkaConnectWithMetrics(String name, String clusterName, int kafkaConnectReplicas) {
        KafkaConnect kafkaConnect = getKafkaConnectFromYaml(PATH_TO_KAFKA_CONNECT_METRICS_CONFIG);
        return deployKafkaConnect(defaultKafkaConnect(kafkaConnect, name, clusterName, kafkaConnectReplicas).build());
    }

    private static KafkaConnectBuilder defaultKafkaConnect(KafkaConnect kafkaConnect, String name, String kafkaClusterName, int kafkaConnectReplicas) {
        return new KafkaConnectBuilder(kafkaConnect)
            .withNewMetadata()
                .withName(name)
                .withNamespace(ResourceManager.kubeClient().getNamespace())
                .withClusterName(kafkaClusterName)
            .endMetadata()
            .withNewSpec()
                .withVersion(Environment.ST_KAFKA_VERSION)
                .withBootstrapServers(KafkaResources.plainBootstrapAddress(name))
                .withReplicas(kafkaConnectReplicas)
            .endSpec();
    }

    private static DoneableKafkaConnect deployKafkaConnect(KafkaConnect kafkaConnect) {
        return new DoneableKafkaConnect(kafkaConnect, kC -> {
            TestUtils.waitFor("KafkaConnect creation", Constants.POLL_INTERVAL_FOR_RESOURCE_CREATION, Constants.TIMEOUT_FOR_CR_CREATION,
                () -> {
                    try {
                        kafkaConnectClient().inNamespace(ResourceManager.kubeClient().getNamespace()).createOrReplace(kC);
                        return true;
                    } catch (KubernetesClientException e) {
                        if (e.getMessage().contains("object is being deleted")) {
                            return false;
                        } else {
                            throw e;
                        }
                    }
                }
            );
            return waitFor(deleteLater(kC));
        });
    }

    public static KafkaConnect kafkaConnectWithoutWait(KafkaConnect kafkaConnect) {
        kafkaConnectClient().inNamespace(ResourceManager.kubeClient().getNamespace()).createOrReplace(kafkaConnect);
        return kafkaConnect;
    }

    public static void deleteKafkaConnectWithoutWait(KafkaConnect kafkaConnect) {
        kafkaConnectClient().inNamespace(ResourceManager.kubeClient().getNamespace()).delete(kafkaConnect);
    }

    private static KafkaConnect getKafkaConnectFromYaml(String yamlPath) {
        return TestUtils.configFromYaml(yamlPath, KafkaConnect.class);
    }

    private static KafkaConnect waitFor(KafkaConnect kafkaConnect) {
        LOGGER.info("Waiting for Kafka Connect {}", kafkaConnect.getMetadata().getName());
        StUtils.waitForDeploymentReady(kafkaConnect.getMetadata().getName() + "-connect", kafkaConnect.getSpec().getReplicas());
        LOGGER.info("Kafka Connect {} is ready", kafkaConnect.getMetadata().getName());
        return kafkaConnect;
    }

    private static KafkaConnect deleteLater(KafkaConnect kafkaConnect) {
        return ResourceManager.deleteLater(kafkaConnectClient(), kafkaConnect);
    }

}
