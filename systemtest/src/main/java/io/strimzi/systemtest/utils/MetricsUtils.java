/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaExporterResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.test.executor.Exec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class MetricsUtils {

    private static final Logger LOGGER = LogManager.getLogger(MetricsUtils.class);
    private static final Object LOCK = new Object();

    /**
     * Collect metrics from specific pod
     * @param podName pod name
     * @param metricsPath enpoint where metrics should be available
     * @return collected metrics
     */
    public static String collectMetrics(String podName, String metricsPath) throws InterruptedException, ExecutionException, IOException {
        List<String> executableCommand = Arrays.asList(cmdKubeClient().toString(), "exec", podName,
                "-n", kubeClient().getNamespace(),
                "--", "curl", kubeClient().getPod(podName).getStatus().getPodIP() + ":9404" + metricsPath);

        Exec exec = new Exec();
        // 20 seconds should be enough for collect data from the pod
        int ret = exec.execute(null, executableCommand, 20_000);

        synchronized (LOCK) {
            LOGGER.info("Metrics collection for pod {} return code - {}", podName, ret);
        }

        return exec.out();
    }

    public static HashMap<String, String> collectKafkaPodsMetrics(String clusterName) {
        LabelSelector kafkaSelector = kubeClient().getStatefulSetSelectors(KafkaResources.kafkaStatefulSetName(clusterName));
        return collectMetricsFromPods(kafkaSelector);
    }

    public static HashMap<String, String> collectZookeeperPodsMetrics(String clusterName) {
        LabelSelector zookeeperSelector = kubeClient().getStatefulSetSelectors(KafkaResources.zookeeperStatefulSetName(clusterName));
        return collectMetricsFromPods(zookeeperSelector);
    }

    public static HashMap<String, String> collectKafkaConnectPodsMetrics(String clusterName) {
        LabelSelector connectSelector = kubeClient().getDeploymentSelectors(KafkaConnectResources.deploymentName(clusterName));
        return collectMetricsFromPods(connectSelector);
    }

    public static HashMap<String, String> collectKafkaExporterPodsMetrics(String clusterName) {
        LabelSelector connectSelector = kubeClient().getDeploymentSelectors(KafkaExporterResources.deploymentName(clusterName));
        return collectMetricsFromPods(connectSelector, "/metrics");
    }


    /**
     * Parse out specific metric from whole metrics file
     * @param pattern regex patern for specific metric
     * @param data all metrics data
     * @return list of parsed values
     */
    public static ArrayList<Double> collectSpecificMetric(Pattern pattern, HashMap<String, String> data) {
        ArrayList<Double> values = new ArrayList<>();

        for (Map.Entry<String, String> entry : data.entrySet()) {
            Matcher t = pattern.matcher(entry.getValue());
            if (t.find()) {
                values.add(Double.parseDouble(t.group(1)));
            }
        }

        return values;
    }

    /**
     * Collect metrics from all pods with specific selector
     * @param labelSelector pod selector
     * @return map with metrics {podName, metrics}
     */
    public static HashMap<String, String> collectMetricsFromPods(LabelSelector labelSelector) {
        return collectMetricsFromPods(labelSelector, "");
    }

    /**
     * Collect metrics from all pods with specific selector
     * @param labelSelector pod selector
     * @param metricsPath additional path where metrics are available
     * @return map with metrics {podName, metrics}
     */
    public static HashMap<String, String> collectMetricsFromPods(LabelSelector labelSelector, String metricsPath) {
        HashMap<String, String> map = new HashMap<>();
        kubeClient().listPods(labelSelector).forEach(p -> {
            try {
                map.put(p.getMetadata().getName(), collectMetrics(p.getMetadata().getName(), metricsPath));
            } catch (InterruptedException | ExecutionException | IOException e) {
                throw new RuntimeException(e);
            }
        });

        return  map;
    }
}
