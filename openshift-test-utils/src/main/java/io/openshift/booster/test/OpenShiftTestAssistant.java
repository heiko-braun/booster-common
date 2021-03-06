/*
 * Copyright 2016-2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openshift.booster.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.jayway.restassured.RestAssured;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.dsl.NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class OpenShiftTestAssistant {

    private final OpenShiftClient client;

    private final String project;

    private String applicationName;

    private Map<String, List<HasMetadata>> created
            = new LinkedHashMap<>();

    public OpenShiftTestAssistant() {
        client = new DefaultKubernetesClient().adapt(OpenShiftClient.class);
        project = client.getNamespace();
    }

    public List<? extends HasMetadata> deploy(String name, File template) throws IOException {
        try (FileInputStream fis = new FileInputStream(template)) {
            NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata, Boolean> declarations = client.load(fis);
            List<HasMetadata> entities = declarations.createOrReplace();
            created.put(name, entities);
            System.out.println(name + " deployed, " + entities.size() + " object(s) created.");

            return entities;
        }
    }

    public String deployApplication() throws IOException {
        applicationName = System.getProperty("app.name");

        List<? extends HasMetadata> entities
                = deploy("application", new File("target/classes/META-INF/fabric8/openshift.yml"));

        Optional<String> first = entities.stream()
                .filter(hm -> hm instanceof DeploymentConfig)
                .map(hm -> (DeploymentConfig) hm)
                .map(dc -> dc.getMetadata().getName()).findFirst();
        if (applicationName == null && first.isPresent()) {
            applicationName = first.get();
        }

        Route route = client.adapt(OpenShiftClient.class).routes()
                .inNamespace(project).withName(applicationName).get();
        assertThat(route).isNotNull();
        RestAssured.baseURI = "http://" + Objects.requireNonNull(route).getSpec().getHost();
        System.out.println("Route url: " + RestAssured.baseURI);

        return applicationName;
    }

    public void cleanup() {
        List<String> keys = new ArrayList<>(created.keySet());
        Collections.reverse(keys);
        for (String key : keys) {
            created.remove(key)
                    .stream()
                    .sorted(Comparator.comparing(HasMetadata::getKind))
                    .forEach(metadata -> {
                        boolean isDeploymentConfigOrReplicationController = metadata instanceof DeploymentConfig || metadata instanceof ReplicationController;
                        System.out.println(String.format("Deleting %s : %s", key, metadata.getKind()));
                        client.resource(metadata).withGracePeriod(0).cascading(!isDeploymentConfigOrReplicationController).delete();
                    });
        }
    }


    public void awaitApplicationReadinessOrFail() {
        await().atMost(5, TimeUnit.MINUTES).until(() -> {
                                                      List<Pod> list = client.pods().inNamespace(project).list().getItems();
                                                      return list.stream()
                                                              .filter(pod -> pod.getMetadata().getName().startsWith(applicationName))
                                                              .filter(this::isRunning)
                                                              .collect(Collectors.toList()).size() >= 1;
                                                  }
        );

    }

    private boolean isRunning(Pod pod) {
        return "running".equalsIgnoreCase(pod.getStatus().getPhase());
    }


    public OpenShiftClient client() {
        return client;
    }

    public String project() {
        return project;
    }

    public String applicationName() {
        return applicationName;
    }

    public void awaitPodReadinessOrFail(Predicate<Pod> filter) {
        await().atMost(5, TimeUnit.MINUTES).until(() -> {
                                                      List<Pod> list = client.pods().inNamespace(project).list().getItems();
                                                      return list.stream()
                                                              .filter(filter)
                                                              .filter(this::isRunning)
                                                              .collect(Collectors.toList()).size() >= 1;
                                                  }
        );
    }
}