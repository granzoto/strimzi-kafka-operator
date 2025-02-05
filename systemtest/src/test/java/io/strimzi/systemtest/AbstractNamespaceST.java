/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMakerResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;

import java.io.File;
import java.util.List;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public abstract class AbstractNamespaceST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(AbstractNamespaceST.class);

    static final String CO_NAMESPACE = "co-namespace-test";
    static final String SECOND_NAMESPACE = "second-namespace-test";
    static final String TOPIC_NAME = "my-topic";
    static final String USER_NAME = "my-user";
    private static final String TOPIC_EXAMPLES_DIR = "../examples/topic/kafka-topic.yaml";

    void checkKafkaInDiffNamespaceThanCO(String clusterName, String namespace) {
        String previousNamespace = cluster.setNamespace(namespace);
        LOGGER.info("Check if Kafka Cluster {} in namespace {}", KafkaResources.kafkaStatefulSetName(clusterName), namespace);

        TestUtils.waitFor("Kafka Cluster status is not in desired state: Ready", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT, () -> {
            Condition kafkaCondition = KafkaResource.kafkaClient().inNamespace(namespace).withName(clusterName).get()
                    .getStatus().getConditions().get(0);
            LOGGER.info("Kafka condition status: {}", kafkaCondition.getStatus());
            LOGGER.info("Kafka condition type: {}", kafkaCondition.getType());
            return kafkaCondition.getType().equals("Ready");
        });

        Condition kafkaCondition = KafkaResource.kafkaClient().inNamespace(namespace).withName(clusterName).get()
                .getStatus().getConditions().get(0);

        assertThat(kafkaCondition.getType(), is("Ready"));
        cluster.setNamespace(previousNamespace);
    }

    void checkMirrorMakerForKafkaInDifNamespaceThanCO(String sourceClusterName) {
        String kafkaSourceName = sourceClusterName;
        String kafkaTargetName = CLUSTER_NAME + "-target";

        String previousNamespace = cluster.setNamespace(SECOND_NAMESPACE);
        KafkaResource.kafkaEphemeral(kafkaTargetName, 1, 1).done();
        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, kafkaSourceName, kafkaTargetName, "my-group", 1, false).done();

        LOGGER.info("Waiting for creation {} in namespace {}", CLUSTER_NAME + "-mirror-maker", SECOND_NAMESPACE);
        StUtils.waitForDeploymentReady(CLUSTER_NAME + "-mirror-maker", 1);
        cluster.setNamespace(previousNamespace);
    }

    void deployNewTopic(String topicNamespace, String kafkaClusterNamespace, String topic) {
        LOGGER.info("Creating topic {} in namespace {}", topic, topicNamespace);
        cluster.setNamespace(topicNamespace);
        cmdKubeClient().create(new File(TOPIC_EXAMPLES_DIR));
        TestUtils.waitFor("wait for 'my-topic' to be created in Kafka", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_TOPIC_CREATION, () -> {
            cluster.setNamespace(kafkaClusterNamespace);
            List<String> topics2 = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
            return topics2.contains(topic);
        });
    }

    void deleteNewTopic(String namespace, String topic) {
        LOGGER.info("Deleting topic {} in namespace {}", topic, namespace);
        cluster.setNamespace(namespace);
        cmdKubeClient().deleteByName("KafkaTopic", topic);
        cluster.setNamespace(CO_NAMESPACE);
    }
}
