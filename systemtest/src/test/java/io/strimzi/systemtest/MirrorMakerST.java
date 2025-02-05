/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaMirrorMakerResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.PasswordSecretSource;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.KafkaListenerTls;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMakerResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@Tag(REGRESSION)
public class MirrorMakerST extends MessagingBaseST {

    private static final Logger LOGGER = LogManager.getLogger(MirrorMakerST.class);

    public static final String NAMESPACE = "mm-cluster-test";
    private static final String TOPIC_NAME = "test-topic";
    private final int messagesCount = 200;
    private String kafkaClusterSourceName = CLUSTER_NAME + "-source";
    private String kafkaClusterTargetName = CLUSTER_NAME + "-target";

    @Test
    void testMirrorMaker() throws Exception {
        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");
        setOperationID(startTimeMeasuring(Operation.MM_DEPLOYMENT));
        String topicSourceName = TOPIC_NAME + "-source" + "-" + rng.nextInt(Integer.MAX_VALUE);

        // Deploy source kafka
        KafkaResource.kafkaEphemeral(kafkaClusterSourceName, 1, 1).done();
        // Deploy target kafka
        KafkaResource.kafkaEphemeral(kafkaClusterTargetName, 1, 1).done();
        // Deploy Topic
        KafkaTopicResource.topic(kafkaClusterSourceName, topicSourceName).done();

        KafkaClientsResource.deployKafkaClients(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName);
        availabilityTest(messagesCount, kafkaClusterTargetName);

        // Deploy Mirror Maker
        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, false).
                editSpec()
                .withResources(new ResourceRequirementsBuilder()
                        .addToLimits("memory", new Quantity("400M"))
                        .addToLimits("cpu", new Quantity("2"))
                        .addToRequests("memory", new Quantity("300M"))
                        .addToRequests("cpu", new Quantity("1"))
                        .build())
                .withNewJvmOptions()
                    .withXmx("200m")
                    .withXms("200m")
                    .withServer(true)
                    .withXx(jvmOptionsXX)
                .endJvmOptions()
                .endSpec().done();

        verifyLabelsOnPods(CLUSTER_NAME, "mirror-maker", null, "KafkaMirrorMaker");
        verifyLabelsForService(CLUSTER_NAME, "mirror-maker", "KafkaMirrorMaker");

        verifyLabelsForConfigMaps(kafkaClusterSourceName, null, kafkaClusterTargetName);
        verifyLabelsForServiceAccounts(kafkaClusterSourceName, null);

        String podName = kubeClient().listPods().stream().filter(n -> n.getMetadata().getName().startsWith(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME))).findFirst().get().getMetadata().getName();
        assertResources(NAMESPACE, podName, CLUSTER_NAME.concat("-mirror-maker"),
                "400M", "2", "300M", "1");
        assertExpectedJavaOpts(podName, KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME),
                "-Xmx200m", "-Xms200m", "-server", "-XX:+UseG1GC");

        TimeMeasuringSystem.stopOperation(getOperationID());

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount,  kafkaClusterSourceName, false, topicSourceName, null, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, false, topicSourceName, null, kafkaClientsPodName);
        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, false, topicSourceName, null, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    /**
     * Test mirroring messages by Mirror Maker over tls transport using mutual tls auth
     */
    @Test
    @Tag(ACCEPTANCE)
    void testMirrorMakerTlsAuthenticated() throws Exception {
        setOperationID(startTimeMeasuring(Operation.MM_DEPLOYMENT));
        String topicSourceName = TOPIC_NAME + "-source" + "-" + rng.nextInt(Integer.MAX_VALUE);
        String kafkaSourceUserName = "my-user-source";
        String kafkaUserTargetName = "my-user-target";

        KafkaListenerAuthenticationTls auth = new KafkaListenerAuthenticationTls();
        KafkaListenerTls listenerTls = new KafkaListenerTls();
        listenerTls.setAuth(auth);

        // Deploy source kafka with tls listener and mutual tls auth
        KafkaResource.kafkaEphemeral(kafkaClusterSourceName, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .withTls(listenerTls)
                            .withNewTls()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        // Deploy target kafka with tls listener and mutual tls auth
        KafkaResource.kafkaEphemeral(kafkaClusterTargetName, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .withTls(listenerTls)
                            .withNewTls()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        // Deploy topic
        KafkaTopicResource.topic(kafkaClusterSourceName, topicSourceName).done();

        // Create Kafka user
        KafkaUser userSource = KafkaUserResource.tlsUser(kafkaClusterSourceName, kafkaSourceUserName).done();
        StUtils.waitForSecretReady(kafkaSourceUserName);

        KafkaUser userTarget = KafkaUserResource.tlsUser(kafkaClusterTargetName, kafkaUserTargetName).done();
        StUtils.waitForSecretReady(kafkaUserTargetName);

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecretSource = new CertSecretSource();
        certSecretSource.setCertificate("ca.crt");
        certSecretSource.setSecretName(KafkaResources.clusterCaCertificateSecretName(kafkaClusterSourceName));

        // Initialize CertSecretSource with certificate and secret names for producer
        CertSecretSource certSecretTarget = new CertSecretSource();
        certSecretTarget.setCertificate("ca.crt");
        certSecretTarget.setSecretName(KafkaResources.clusterCaCertificateSecretName(kafkaClusterTargetName));

        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, userSource, userTarget).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName, true, "my-topic-test-1", userSource);
        availabilityTest(messagesCount, kafkaClusterTargetName, true, "my-topic-test-2", userTarget);

        // Deploy Mirror Maker with tls listener and mutual tls auth
        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, true)
                .editSpec()
                .editConsumer()
                .withNewTls()
                .withTrustedCertificates(certSecretSource)
                .endTls()
                .endConsumer()
                .editProducer()
                .withNewTls()
                .withTrustedCertificates(certSecretTarget)
                .endTls()
                .endProducer()
                .endSpec()
                .done();

        TimeMeasuringSystem.stopOperation(getOperationID());

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount,  kafkaClusterSourceName, true, topicSourceName, userSource, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, true, topicSourceName, userSource, kafkaClientsPodName);
        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, true, topicSourceName, userTarget, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    /**
     * Test mirroring messages by Mirror Maker over tls transport using scram-sha auth
     */
    @Test
    void testMirrorMakerTlsScramSha() throws Exception {
        setOperationID(startTimeMeasuring(Operation.MM_DEPLOYMENT));
        String topicName = TOPIC_NAME + "-" + rng.nextInt(Integer.MAX_VALUE);
        String kafkaUserSource = "my-user-source";
        String kafkaUserTarget = "my-user-target";

        // Deploy source kafka with tls listener and SCRAM-SHA authentication
        KafkaResource.kafkaEphemeral(kafkaClusterSourceName, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .withNewTls().withAuth(new KafkaListenerAuthenticationScramSha512()).endTls()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        // Deploy target kafka with tls listener and SCRAM-SHA authentication
        KafkaResource.kafkaEphemeral(kafkaClusterTargetName, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .withNewTls().withAuth(new KafkaListenerAuthenticationScramSha512()).endTls()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        // Create Kafka user for source cluster
        KafkaUser userSource = KafkaUserResource.scramShaUser(kafkaClusterSourceName, kafkaUserSource).done();
        StUtils.waitForSecretReady(kafkaUserSource);

        // Create Kafka user for target cluster
        KafkaUser userTarget = KafkaUserResource.scramShaUser(kafkaClusterTargetName, kafkaUserTarget).done();
        StUtils.waitForSecretReady(kafkaUserTarget);

        // Initialize PasswordSecretSource to set this as PasswordSecret in Mirror Maker spec
        PasswordSecretSource passwordSecretSource = new PasswordSecretSource();
        passwordSecretSource.setSecretName(kafkaUserSource);
        passwordSecretSource.setPassword("password");

        // Initialize PasswordSecretSource to set this as PasswordSecret in Mirror Maker spec
        PasswordSecretSource passwordSecretTarget = new PasswordSecretSource();
        passwordSecretTarget.setSecretName(kafkaUserTarget);
        passwordSecretTarget.setPassword("password");

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecretSource = new CertSecretSource();
        certSecretSource.setCertificate("ca.crt");
        certSecretSource.setSecretName(KafkaResources.clusterCaCertificateSecretName(kafkaClusterSourceName));

        // Initialize CertSecretSource with certificate and secret names for producer
        CertSecretSource certSecretTarget = new CertSecretSource();
        certSecretTarget.setCertificate("ca.crt");
        certSecretTarget.setSecretName(KafkaResources.clusterCaCertificateSecretName(kafkaClusterTargetName));

        // Deploy client
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, userSource, userTarget).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName, true, "my-topic-test-1", userSource);
        availabilityTest(messagesCount, kafkaClusterTargetName, true, "my-topic-test-2", userTarget);

        // Deploy Mirror Maker with TLS and ScramSha512
        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, true)
            .editSpec()
                .editConsumer()
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(kafkaUserSource)
                        .withPasswordSecret(passwordSecretSource)
                    .endKafkaClientAuthenticationScramSha512()
                    .withNewTls()
                        .withTrustedCertificates(certSecretSource)
                    .endTls()
                .endConsumer()
                .editProducer()
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(kafkaUserTarget)
                        .withPasswordSecret(passwordSecretTarget)
                    .endKafkaClientAuthenticationScramSha512()
                    .withNewTls()
                        .withTrustedCertificates(certSecretTarget)
                    .endTls()
                .endProducer()
            .endSpec().done();

        // Deploy topic
        KafkaTopicResource.topic(kafkaClusterSourceName, topicName).done();

        TimeMeasuringSystem.stopOperation(getOperationID());

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount, kafkaClusterSourceName, true, topicName, userSource, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, true, topicName, userSource, kafkaClientsPodName);

        TestUtils.waitFor("Waiting for Mirror Maker will copy messages from " + kafkaClusterSourceName + " to " + kafkaClusterTargetName,
            Constants.POLL_INTERVAL_FOR_RESOURCE_CREATION, Constants.TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS,
            () -> sent == receiveMessages(messagesCount, kafkaClusterTargetName, true, topicName, userTarget, kafkaClientsPodName));

        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, true, topicName, userTarget, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    @Test
    void testWhiteList() throws Exception {
        String topicName = "topic-example-1";
        String topicNotInWhitelist = "topic-example-2";

        LOGGER.info("Creating kafka source cluster {}", kafkaClusterSourceName);
        KafkaResource.kafkaEphemeral(kafkaClusterSourceName, 1, 1).done();

        LOGGER.info("Creating kafka target cluster {}", kafkaClusterTargetName);
        KafkaResource.kafkaEphemeral(kafkaClusterTargetName, 1, 1).done();

        KafkaTopicResource.topic(kafkaClusterSourceName, topicName).done();
        KafkaTopicResource.topic(kafkaClusterSourceName, topicNotInWhitelist).done();

        StUtils.waitForKafkaTopicCreation(topicName);
        StUtils.waitForKafkaTopicCreation(topicNotInWhitelist);

        KafkaClientsResource.deployKafkaClients(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName);
        availabilityTest(messagesCount, kafkaClusterTargetName);

        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, false)
                .editSpec()
                    .withNewWhitelist(topicName)
                .endSpec()
                .done();

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount, kafkaClusterSourceName, false, topicName, null, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, false, topicName, null, kafkaClientsPodName);
        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, false, topicName, null, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);

        sent = sendMessages(messagesCount, kafkaClusterSourceName, false, topicNotInWhitelist, null, kafkaClientsPodName);
        receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, false, topicNotInWhitelist, null, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertThat("Received 0 messages in target kafka because topic " + topicNotInWhitelist + " is not in whitelist",
                receiveMessages(messagesCount, kafkaClusterTargetName, false, topicNotInWhitelist, null, kafkaClientsPodName), is(0));
    }

    @Test
    void testCustomAndUpdatedValues() {
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 1, 1)
            .editSpec()
                .withNewEntityOperator()
                .endEntityOperator()
            .endSpec().done();

        String usedVariable = "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER";

        LinkedHashMap<String, String> envVarGeneral = new LinkedHashMap<>();
        envVarGeneral.put("TEST_ENV_1", "test.env.one");
        envVarGeneral.put("TEST_ENV_2", "test.env.two");
        envVarGeneral.put(usedVariable, "test.value");

        LinkedHashMap<String, String> envVarUpdated = new LinkedHashMap<>();
        envVarUpdated.put("TEST_ENV_2", "updated.test.env.two");
        envVarUpdated.put("TEST_ENV_3", "test.env.three");

        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put("acks", "all");

        Map<String, Object> updatedProducerConfig = new HashMap<>();
        updatedProducerConfig.put("acks", "0");

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put("auto.offset.reset", "latest");

        Map<String, Object> updatedConsumerConfig = new HashMap<>();
        updatedConsumerConfig.put("auto.offset.reset", "earliest");

        int initialDelaySeconds = 30;
        int timeoutSeconds = 10;
        int updatedInitialDelaySeconds = 31;
        int updatedTimeoutSeconds = 11;
        int periodSeconds = 10;
        int successThreshold = 1;
        int failureThreshold = 3;
        int updatedPeriodSeconds = 5;
        int updatedFailureThreshold = 1;

        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, CLUSTER_NAME, CLUSTER_NAME, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, false)
            .editSpec()
                .editProducer()
                    .withConfig(producerConfig)
                .endProducer()
                .editConsumer()
                    .withConfig(consumerConfig)
                .endConsumer()
                .withNewTemplate()
                    .withNewMirrorMakerContainer()
                        .withEnv(StUtils.createContainerEnvVarsFromMap(envVarGeneral))
                    .endMirrorMakerContainer()
                .endTemplate()
                .withNewReadinessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endReadinessProbe()
                .withNewLivenessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endLivenessProbe()
            .endSpec()
            .done();

        Map<String, String> connectSnapshot = StUtils.depSnapshot(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME));

        // Remove variable which is already in use
        envVarGeneral.remove(usedVariable);
        LOGGER.info("Verify values before update");
        checkReadinessLivenessProbe(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), initialDelaySeconds, timeoutSeconds,
                periodSeconds, successThreshold, failureThreshold);
        checkSpecificVariablesInContainer(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), envVarGeneral);
        checkComponentConfiguration(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER", producerConfig);
        checkComponentConfiguration(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), "KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER", consumerConfig);

        StUtils.checkCOlogForUsedVariable(usedVariable);

        LOGGER.info("Updating values in MirrorMaker container");
        replaceMirrorMakerResource(CLUSTER_NAME, kmm -> {
            kmm.getSpec().getTemplate().getMirrorMakerContainer().setEnv(StUtils.createContainerEnvVarsFromMap(envVarUpdated));
            kmm.getSpec().getProducer().setConfig(updatedProducerConfig);
            kmm.getSpec().getConsumer().setConfig(updatedConsumerConfig);
            kmm.getSpec().getLivenessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kmm.getSpec().getReadinessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kmm.getSpec().getLivenessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kmm.getSpec().getReadinessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kmm.getSpec().getLivenessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kmm.getSpec().getReadinessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kmm.getSpec().getLivenessProbe().setFailureThreshold(updatedFailureThreshold);
            kmm.getSpec().getReadinessProbe().setFailureThreshold(updatedFailureThreshold);
        });

        StUtils.waitTillDepHasRolled(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), 1, connectSnapshot);

        LOGGER.info("Verify values after update");
        checkReadinessLivenessProbe(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), updatedInitialDelaySeconds, updatedTimeoutSeconds,
                updatedPeriodSeconds, successThreshold, updatedFailureThreshold);
        checkSpecificVariablesInContainer(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), envVarUpdated);
        checkComponentConfiguration(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER", updatedProducerConfig);
        checkComponentConfiguration(KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), KafkaMirrorMakerResources.deploymentName(CLUSTER_NAME), "KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER", updatedConsumerConfig);
    }
    
    @BeforeAll
    void setupEnvironment() {
        ResourceManager.setClassResources();
        prepareEnvForOperator(NAMESPACE);

        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        KubernetesResource.clusterOperator(NAMESPACE)
            .editSpec()
                .editTemplate()
                    .editSpec()
                        .editFirstContainer()
                            .addToEnv(new EnvVarBuilder().withName("TEST_ENV_3").withValue("test.value").build())
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec().done();
    }
}
