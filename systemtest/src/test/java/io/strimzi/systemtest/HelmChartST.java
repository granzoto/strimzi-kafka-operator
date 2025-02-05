/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.utils.StUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;

import java.util.List;

import static io.strimzi.systemtest.Constants.HELM;

@Tag(HELM)
class HelmChartST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(HelmChartST.class);

    static final String NAMESPACE = "helm-chart-cluster-test";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final String TOPIC_NAME = "test-topic";

    @Test
    void testDeployKafkaClusterViaHelmChart() {
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3).done();
        KafkaTopicResource.topic(CLUSTER_NAME, TOPIC_NAME).done();
        StUtils.waitForAllStatefulSetPodsReady(KafkaResources.zookeeperStatefulSetName(CLUSTER_NAME), 3);
        StUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);
    }

    @BeforeAll
    void setup() {
        LOGGER.info("Creating resources before the test class");
        cluster.createNamespace(NAMESPACE);
        deployClusterOperatorViaHelmChart();
    }

    @Override
    protected void tearDownEnvironmentAfterAll() {
        deleteClusterOperatorViaHelmChart();
        cluster.deleteNamespaces();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        deleteClusterOperatorViaHelmChart();
        cluster.deleteNamespaces();
        cluster.createNamespace(NAMESPACE);
    }
}
