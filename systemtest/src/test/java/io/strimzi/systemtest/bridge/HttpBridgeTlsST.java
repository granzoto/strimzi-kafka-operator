/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.bridge;

import io.fabric8.kubernetes.api.model.Service;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.utils.BridgeUtils;
import io.strimzi.systemtest.utils.HttpUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.crd.KafkaBridgeResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;

import java.util.Random;

import static io.strimzi.systemtest.Constants.BRIDGE;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.bridge.HttpBridgeST.NAMESPACE;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(BRIDGE)
@Tag(REGRESSION)
@Tag(NODEPORT_SUPPORTED)
@ExtendWith(VertxExtension.class)
class HttpBridgeTlsST extends HttpBridgeBaseST {
    private static final Logger LOGGER = LogManager.getLogger(HttpBridgeTlsST.class);

    private String bridgeHost = "";
    private int bridgePort = Constants.HTTP_BRIDGE_DEFAULT_PORT;
    private String userName = "bob";

    @Test
    void testSendSimpleMessageTls() throws Exception {
        int messageCount = 50;
        String topicName = "topic-simple-send";
        // Create topic
        KafkaTopicResource.topic(CLUSTER_NAME, topicName).done();

        JsonObject records = HttpUtils.generateHttpMessages(messageCount);
        JsonObject response = HttpUtils.sendMessagesHttpRequest(records, bridgeHost, bridgePort, topicName, client);
        BridgeUtils.checkSendResponse(response, messageCount);
        receiveMessagesExternalTls(NAMESPACE, topicName, messageCount, userName);
    }

    @Test
    void testReceiveSimpleMessageTls() throws Exception {
        int messageCount = 50;
        String topicName = "topic-simple-receive";
        // Create topic
        KafkaTopicResource.topic(CLUSTER_NAME, topicName).done();

        String name = "my-kafka-consumer";
        String groupId = "my-group-" + new Random().nextInt(Integer.MAX_VALUE);

        JsonObject config = new JsonObject();
        config.put("name", name);
        config.put("format", "json");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Create consumer
        JsonObject response = createBridgeConsumer(config, bridgeHost, bridgePort, groupId);
        assertThat("Consumer wasn't created correctly", response.getString("instance_id"), is(name));
        // Create topics json
        JsonArray topic = new JsonArray();
        topic.add(topicName);
        JsonObject topics = new JsonObject();
        topics.put("topics", topic);
        // Subscribe
        assertThat(HttpUtils.subscribeHttpConsumer(topics, bridgeHost, bridgePort, groupId, name, client), is(true));
        // Send messages to Kafka
        sendMessagesExternalTls(NAMESPACE, topicName, messageCount, userName);
        // Try to consume messages
        JsonArray bridgeResponse = HttpUtils.receiveMessagesHttpRequest(bridgeHost, bridgePort, groupId, name, client);
        if (bridgeResponse.size() == 0) {
            // Real consuming
            bridgeResponse = HttpUtils.receiveMessagesHttpRequest(bridgeHost, bridgePort, groupId, name, client);
        }
        assertThat("Sent message count is not equal with received message count", bridgeResponse.size(), is(messageCount));
        // Delete consumer
        assertThat(deleteConsumer(bridgeHost, bridgePort, groupId, name), is(true));
    }

    @BeforeAll
    void createClassResources() throws InterruptedException {
        LOGGER.info("Deploy Kafka and Kafka Bridge before tests");

        // Deploy kafka
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                            .withTls(true)
                        .endKafkaListenerExternalNodePort()
                        .withNewTls()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        // Create Kafka user
        KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();
        StUtils.waitForSecretReady(userName);

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecret = new CertSecretSource();
        certSecret.setCertificate("ca.crt");
        certSecret.setSecretName(KafkaResources.clusterCaCertificateSecretName(CLUSTER_NAME));

        // Deploy http bridge
        KafkaBridgeResource.kafkaBridge(CLUSTER_NAME, KafkaResources.tlsBootstrapAddress(CLUSTER_NAME), 1)
            .editSpec()
            .withNewTls()
            .withTrustedCertificates(certSecret)
            .endTls()
            .endSpec().done();

        Service service = BridgeUtils.createBridgeNodePortService(CLUSTER_NAME, NAMESPACE, bridgeExternalService);
        KubernetesResource.createServiceResource(service, NAMESPACE).done();
        StUtils.waitForNodePortService(bridgeExternalService);

        bridgePort = BridgeUtils.getBridgeNodePort(NAMESPACE, bridgeExternalService);
        bridgeHost = kubeClient(NAMESPACE).getNodeAddress();
    }
}
