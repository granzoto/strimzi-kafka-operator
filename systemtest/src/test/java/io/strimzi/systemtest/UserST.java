/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.KafkaUserScramSha512ClientAuthentication;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;

import java.util.List;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.SCALABILITY;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.valid4j.matchers.jsonpath.JsonPathMatchers.hasJsonPath;

@Tag(REGRESSION)
class UserST extends AbstractST {

    public static final String NAMESPACE = "user-cluster-test";
    private static final Logger LOGGER = LogManager.getLogger(UserST.class);

    @Disabled
    @Test
    void testUserWithNameMoreThan64Chars() {
        String userWithLongName = "user" + "abcdefghijklmnopqrstuvxyzabcdefghijklmnopqrstuvxyzabcdefghijk"; // 65 character username
        String userWithCorrectName = "user-with-correct-name" + "abcdefghijklmnopqrstuvxyzabcdefghijklmnopq"; // 64 character username
        String saslUserWithLongName = "sasl-user" + "abcdefghijklmnopqrstuvxyzabcdefghijklmnopqrstuvxyzabcdef"; // 65 character username

        // Create user with correct name
        KafkaUserResource.tlsUser(CLUSTER_NAME, userWithCorrectName).done();
        StUtils.waitForSecretReady(userWithCorrectName);

        StUtils.waitUntilKafkaUserStatusConditionIsPresent(userWithCorrectName);

        Condition condition = KafkaUserResource.kafkaUserClient().inNamespace(NAMESPACE).withName(userWithCorrectName).get().getStatus().getConditions().get(0);
        LOGGER.info(condition.getMessage() != null);

        verifyCRStatusCondition(condition,
                "True",
                "Ready");

        // Create sasl user with long name
        KafkaUserResource.scramShaUser(CLUSTER_NAME, saslUserWithLongName).done();

        StUtils.waitUntilKafkaUserStatusConditionIsPresent(saslUserWithLongName);

        condition = KafkaUserResource.kafkaUserClient().inNamespace(NAMESPACE).withName(saslUserWithLongName).get().getStatus().getConditions().get(0);

        verifyCRStatusCondition(condition,
                "must be no more than 63 characters",
                "KubernetesClientException",
                "True",
                "NotReady");

        KafkaUserResource.tlsUser(CLUSTER_NAME, userWithLongName).done();

        StUtils.waitUntilKafkaUserStatusConditionIsPresent(userWithLongName);

        condition = KafkaUserResource.kafkaUserClient().inNamespace(NAMESPACE).withName(userWithLongName).get().getStatus().getConditions().get(0);

        verifyCRStatusCondition(condition,
                "must be no more than 63 characters",
                "KubernetesClientException",
                "True",
                "NotReady");
    }

    @Test
    @Tag(ACCEPTANCE)
    void testUpdateUser() {
        String kafkaUser = "test-user";

        KafkaUser user = KafkaUserResource.tlsUser(CLUSTER_NAME, kafkaUser).done();
        StUtils.waitForSecretReady(kafkaUser);

        String kafkaUserSecret = TestUtils.toJsonString(kubeClient().getSecret(kafkaUser));
        assertThat(kafkaUserSecret, hasJsonPath("$.data['ca.crt']", notNullValue()));
        assertThat(kafkaUserSecret, hasJsonPath("$.data['user.crt']", notNullValue()));
        assertThat(kafkaUserSecret, hasJsonPath("$.data['user.key']", notNullValue()));
        assertThat(kafkaUserSecret, hasJsonPath("$.metadata.name", equalTo(kafkaUser)));
        assertThat(kafkaUserSecret, hasJsonPath("$.metadata.namespace", equalTo(NAMESPACE)));

        KafkaUser kUser = Crds.kafkaUserOperation(kubeClient().getClient()).inNamespace(kubeClient().getNamespace()).withName(kafkaUser).get();
        String kafkaUserAsJson = TestUtils.toJsonString(kUser);

        assertThat(kafkaUserAsJson, hasJsonPath("$.metadata.name", equalTo(kafkaUser)));
        assertThat(kafkaUserAsJson, hasJsonPath("$.metadata.namespace", equalTo(NAMESPACE)));
        assertThat(kafkaUserAsJson, hasJsonPath("$.spec.authentication.type", equalTo("tls")));

        long observedGeneration = KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(kafkaUser).get().getStatus().getObservedGeneration();
        LOGGER.info("observation: {}", observedGeneration);

        replaceUserResource(kafkaUser, ku -> {
            ku.getMetadata().setResourceVersion(null);
            ku.getSpec().setAuthentication(new KafkaUserScramSha512ClientAuthentication());
        });

        StUtils.waitForKafkaUserIncreaseObserverGeneration(observedGeneration, kafkaUser);
        StUtils.waitForKafkaUserCreation(kafkaUser);

        kafkaUserSecret = TestUtils.toJsonString(kubeClient().getSecret(kafkaUser));
        assertThat(kafkaUserSecret, hasJsonPath("$.data.password", notNullValue()));

        kUser = Crds.kafkaUserOperation(kubeClient().getClient()).inNamespace(kubeClient().getNamespace()).withName(kafkaUser).get();
        kafkaUserAsJson = TestUtils.toJsonString(kUser);
        assertThat(kafkaUserAsJson, hasJsonPath("$.metadata.name", equalTo(kafkaUser)));
        assertThat(kafkaUserAsJson, hasJsonPath("$.metadata.namespace", equalTo(NAMESPACE)));
        assertThat(kafkaUserAsJson, hasJsonPath("$.spec.authentication.type", equalTo("scram-sha-512")));

        Crds.kafkaUserOperation(kubeClient().getClient()).inNamespace(kubeClient().getNamespace()).delete(kUser);
        StUtils.waitForKafkaUserDeletion(kafkaUser);
    }

    @Tag(SCALABILITY)
    @Tag(NODEPORT_SUPPORTED)
    @Test
    void testBigAmountOfScramShaUsers() {
        createBigAmountOfUsers("SCRAM_SHA");
    }

    @Tag(SCALABILITY)
    @Tag(NODEPORT_SUPPORTED)
    @Test
    void testBigAmountOfTlsUsers() {
        createBigAmountOfUsers("TLS");
    }

    void createBigAmountOfUsers(String typeOfUser) {
        int numberOfUsers = 100;

        for (int i = 0; i < numberOfUsers; i++) {
            String userName = "alisa" + i;
            LOGGER.info("Creating user with name {}", userName);
            if (typeOfUser.equals("TLS")) {
                KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();
            } else {
                KafkaUserResource.scramShaUser(CLUSTER_NAME, userName).done();
            }
            StUtils.waitForSecretReady(userName);
            LOGGER.info("Checking status of deployed Kafka User {}", userName);
            Condition kafkaCondition = KafkaUserResource.kafkaUserClient().inNamespace(NAMESPACE).withName(userName).get()
                    .getStatus().getConditions().get(0);
            LOGGER.info("Kafka User condition status: {}", kafkaCondition.getStatus());
            LOGGER.info("Kafka User condition type: {}", kafkaCondition.getType());
            assertThat(kafkaCondition.getType(), is("Ready"));
            LOGGER.info("Kafka User {} is in desired state: {}", userName, kafkaCondition.getType());
        }
    }

    private void deployTestSpecificResources() {
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec().done();
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        prepareEnvForOperator(NAMESPACE);

        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        KubernetesResource.clusterOperator(NAMESPACE).done();
        deployTestSpecificResources();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        super.recreateTestEnv(coNamespace, bindingsNamespaces);
        deployTestSpecificResources();
    }
}
