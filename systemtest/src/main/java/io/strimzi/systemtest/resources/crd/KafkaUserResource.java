/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources.crd;

import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaUserList;
import io.strimzi.api.kafka.model.DoneableKafkaUser;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.KafkaUserBuilder;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.strimzi.systemtest.resources.ResourceManager;

public class KafkaUserResource {
    private static final Logger LOGGER = LogManager.getLogger(KafkaUserResource.class);

    public static final String PATH_TO_KAFKA_USER_CONFIG = "../examples/user/kafka-user.yaml";

    public static MixedOperation<KafkaUser, KafkaUserList, DoneableKafkaUser, Resource<KafkaUser, DoneableKafkaUser>> kafkaUserClient() {
        return Crds.kafkaUserOperation(ResourceManager.kubeClient().getClient());
    }

    public static DoneableKafkaUser tlsUser(String clusterName, String name) {
        return user(defaultUser(clusterName, name)
            .editSpec()
                .withNewKafkaUserTlsClientAuthentication()
                .endKafkaUserTlsClientAuthentication()
            .endSpec()
            .build());
    }

    public static DoneableKafkaUser scramShaUser(String clusterName, String name) {
        return user(defaultUser(clusterName, name)
            .editSpec()
                .withNewKafkaUserScramSha512ClientAuthentication()
                .endKafkaUserScramSha512ClientAuthentication()
            .endSpec()
            .build());
    }

    private static KafkaUserBuilder defaultUser(String clusterName, String name) {
        KafkaUser kafkaUser = getKafkaUserFromYaml(PATH_TO_KAFKA_USER_CONFIG);
        return new KafkaUserBuilder(kafkaUser)
            .withNewMetadata()
                .withClusterName(clusterName)
                .withName(name)
                .withNamespace(ResourceManager.kubeClient().getNamespace())
                .addToLabels("strimzi.io/cluster", clusterName)
            .endMetadata();
    }

    static DoneableKafkaUser user(KafkaUser user) {
        return new DoneableKafkaUser(user, ku -> {
            kafkaUserClient().inNamespace(ResourceManager.kubeClient().getNamespace()).createOrReplace(ku);
            LOGGER.info("Created KafkaUser {}", ku.getMetadata().getName());
            return waitFor(deleteLater(ku));
        });
    }

    private static KafkaUser getKafkaUserFromYaml(String yamlPath) {
        return TestUtils.configFromYaml(yamlPath, KafkaUser.class);
    }

    private static KafkaUser waitFor(KafkaUser kafkaUser) {
        LOGGER.info("Waiting for Kafka User {}", kafkaUser.getMetadata().getName());
        StUtils.waitForSecretReady(kafkaUser.getMetadata().getName());
        LOGGER.info("Kafka User {} is ready", kafkaUser.getMetadata().getName());
        return kafkaUser;
    }

    private static KafkaUser deleteLater(KafkaUser kafkaUser) {
        return ResourceManager.deleteLater(kafkaUserClient(), kafkaUser);
    }
}
