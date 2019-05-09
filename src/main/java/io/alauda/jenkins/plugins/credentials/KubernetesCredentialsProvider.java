package io.alauda.jenkins.plugins.credentials;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.TermMilestone;
import hudson.init.Terminator;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ModelObject;
import hudson.security.ACL;
import io.alauda.jenkins.devops.config.KubernetesCluster;
import io.alauda.jenkins.devops.config.KubernetesClusterConfiguration;
import io.alauda.jenkins.devops.config.KubernetesClusterConfigurationListener;
import io.alauda.jenkins.devops.config.utils.CredentialsUtils;
import io.alauda.jenkins.plugins.credentials.convertor.CredentialsConversionException;
import io.alauda.jenkins.plugins.credentials.convertor.SecretToCredentialConverter;
import io.alauda.jenkins.plugins.credentials.filter.KubernetesSecretMatcher;
import io.alauda.kubernetes.api.model.LabelSelector;
import io.alauda.kubernetes.api.model.LabelSelectorBuilder;
import io.alauda.kubernetes.api.model.LabelSelectorRequirementBuilder;
import io.alauda.kubernetes.api.model.Secret;
import io.alauda.kubernetes.api.model.SecretList;
import io.alauda.kubernetes.client.ConfigBuilder;
import io.alauda.kubernetes.client.*;
import io.alauda.kubernetes.client.dsl.FilterWatchListMultiDeletable;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_GONE;

@Extension
public class KubernetesCredentialsProvider extends CredentialsProvider implements Watcher<Secret>, KubernetesClusterConfigurationListener {

    private static final Logger LOG
            = Logger.getLogger(KubernetesCredentialsStore.class.getName());
    private static final String DEFAULT_RESOURCE_VERSION = "0";

    // Maps of credentials keyed by namespace and credentials ID
    private ConcurrentHashMap<String, ConcurrentHashMap<String, IdCredentials>> credentials = new ConcurrentHashMap<>();

    private KubernetesClient client;
    private Watch watch;

    @Initializer(after = InitMilestone.PLUGINS_PREPARED, fatal = false)
    public void startWatchingForSecrets() {
        KubernetesCluster cluster = KubernetesClusterConfiguration.get().getCluster();
        startWatchingForSecrets(cluster);
    }

    private void startWatchingForSecrets(KubernetesCluster cluster) {
        KubernetesClient _client = initializeKubernetesClientFromCLuster(cluster);
        ConcurrentHashMap<String, ConcurrentHashMap<String, IdCredentials>> _credentials = new ConcurrentHashMap<>();

        String labelSelector = KubernetesCredentialsProviderConfiguration.get().getLabelSelector();
        LabelSelector selector = null;

        if (!StringUtils.isEmpty(labelSelector)) {
            selector = new LabelSelectorBuilder()
                    .addToMatchExpressions(new LabelSelectorRequirementBuilder().addToValues(labelSelector).build())
                    .build();
        }


        SecretList secretList = _client.secrets().inAnyNamespace().list();

        if (secretList == null) {
            credentials = _credentials;
            client = _client;

            watch = initializeWatch(selector, DEFAULT_RESOURCE_VERSION);
            return;

        }

        String resourceVersion = secretList.getMetadata().getResourceVersion();
        List<Secret> secrets = secretList.getItems();

        for (Secret s : secrets) {
            IdCredentials cred = convertSecret(s);
            if (cred != null) {
                String namespace = s.getMetadata().getNamespace();

                _credentials.putIfAbsent(namespace, new ConcurrentHashMap<>());
                _credentials.get(namespace).put(SecretUtils.getCredentialId(s), cred);
            }
        }

        credentials = _credentials;
        client = _client;

        watch = initializeWatch(selector, resourceVersion);
    }

    private Watch initializeWatch(LabelSelector selector, String resourceVersion) {
        FilterWatchListMultiDeletable<Secret, SecretList, Boolean, Watch, Watcher<Secret>> watchList = client.secrets()
                .inAnyNamespace();
        if (selector != null) {
            watchList.withLabelSelector(selector);
        }


        return watchList.withResourceVersion(resourceVersion)
                .watch(this);
    }

    @Terminator(after = TermMilestone.STARTED)
    public void stopWatchingForSecrets() {
        if (watch != null) {
            watch.close();
            watch = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }


    @Nonnull
    @Override
    public <C extends Credentials> List<C> getCredentials(@Nonnull Class<C> type, ItemGroup itemGroup, Authentication authentication) {
        LOG.log(Level.FINEST, "getCredentials called with type {0} and authentication {1}", new Object[]{type.getName(), authentication});
        if (ACL.SYSTEM.equals(authentication)) {
            List<C> list = new ArrayList<>();

            // when item group is Jenkins root, we show all credentials
            if (itemGroup instanceof Jenkins) {
                credentials.entrySet().stream()
                        .filter(namespaceCredentials ->
                                namespaceCredentials.getKey().equals(KubernetesCredentialsProviderConfiguration.get().getSharedNamespace()))
                        .flatMap(namespaceCredentials -> namespaceCredentials.getValue().entrySet().stream())
                        .forEach(stringIdCredentials -> {
                            if (type.isAssignableFrom(stringIdCredentials.getValue().getClass())) {
                                list.add(type.cast(stringIdCredentials.getValue()));
                            }
                        });
                return list;
            }

            Set<String> ids = new HashSet<>();
            while (itemGroup != null) {
                if (itemGroup instanceof AbstractFolder) {
                    final AbstractFolder<?> folder = (AbstractFolder) itemGroup;
                    // we use namespace as folder name
                    String namespace = folder.getName();
                    credentials.entrySet().stream()
                            .filter(namespaceCredentials -> namespaceCredentials.getKey().equals(namespace))
                            .flatMap(namespaceCredentials -> namespaceCredentials.getValue().entrySet().stream())
                            .filter(stringIdCredentials -> type.isAssignableFrom(stringIdCredentials.getValue().getClass()))
                            .forEach(stringIdCredentials -> {
                              if (ids.add(stringIdCredentials.getKey())) {
                                  list.add(type.cast(stringIdCredentials.getValue()));
                              }
                            });
                }

                if (itemGroup instanceof Item) {
                    itemGroup = ((Item) itemGroup).getParent();
                } else {
                    break;
                }
            }
            return list;
        }
        return Collections.emptyList();
    }





    @Override
    public void eventReceived(Action action, Secret secret) {
        String credentialId = SecretUtils.getCredentialId(secret);
        String namespace = secret.getMetadata().getNamespace();
        switch (action) {
            case ADDED: {
                LOG.log(Level.FINE, "Secret Added - {0}", credentialId);
                IdCredentials cred = convertSecret(secret);
                if (cred != null) {
                    credentialsInNamespace(namespace).put(credentialId, cred);
                }
                break;
            }
            case MODIFIED: {
                LOG.log(Level.FINE, "Secret Modified - {0}", credentialId);
                IdCredentials cred = convertSecret(secret);
                if (cred != null) {
                    credentialsInNamespace(namespace).put(credentialId, cred);
                }
                break;
            }
            case DELETED: {
                LOG.log(Level.FINE, "Secret Deleted - {0}", credentialId);
                if (credentials.containsKey(namespace)) {
                    credentials.get(namespace).remove(credentialId);
                }
                break;
            }
            case ERROR:
                LOG.log(Level.WARNING, "Action received of type Error. {0}", secret);
        }
    }

    @Nonnull
    private Map<String, IdCredentials> credentialsInNamespace(String namespace) {
        credentials.putIfAbsent(namespace, new ConcurrentHashMap<>());
        return credentials.get(namespace);
    }

    @Override
    public void onClose(KubernetesClientException cause) {
        LOG.log(Level.SEVERE, "Watcher stopped");

        if (cause != null) {
            LOG.warning(cause.toString());
            // restart watcher when http connect is gone
            if (cause.getStatus() != null && cause.getStatus().getCode() == HTTP_GONE) {
                stopWatchingForSecrets();
                startWatchingForSecrets();
            }
        }
    }


    @Override
    public void onConfigChange(KubernetesCluster kubernetesCluster) {
        stopWatchingForSecrets();
        startWatchingForSecrets(kubernetesCluster);
    }

    /**
     * Create a Kubernetes client by cluster configuration
     *
     * @param cluster Kubernetes cluster
     * @return Kubernetes client
     */
    private KubernetesClient initializeKubernetesClientFromCLuster(KubernetesCluster cluster) {
        ConfigBuilder configBuilder = new ConfigBuilder();

        if (cluster == null) {
            return new DefaultKubernetesClient(configBuilder.build());
        }

        if (!StringUtils.isEmpty(cluster.getMasterUrl())) {
            configBuilder.withMasterUrl(cluster.getMasterUrl());
        }

        if (!StringUtils.isEmpty(cluster.getCredentialsId())) {
            try {
                String token = CredentialsUtils.getToken(cluster.getCredentialsId());
                configBuilder.withOauthToken(token);
            } catch (GeneralSecurityException e) {
                e.printStackTrace();
            }
        }

        configBuilder.withTrustCerts(cluster.isSkipTlsVerify());

        if (!StringUtils.isEmpty(cluster.getServerCertificateAuthority())) {
            if (Files.exists(Paths.get(cluster.getServerCertificateAuthority()))) {
                configBuilder.withCaCertFile(cluster.getServerCertificateAuthority());
            } else {
                configBuilder.withCaCertData(cluster.getServerCertificateAuthority());
            }
        }

        return new DefaultKubernetesClient(configBuilder.build());
    }


    private IdCredentials convertSecret(Secret s) {
        if (!KubernetesSecretMatcher.isMatch(s)) {
            return null;
        }

        String type = getSecretType(s);
        SecretToCredentialConverter lookup = SecretToCredentialConverter.lookup(type);
        if (lookup != null) {
            try {
                return lookup.convert(s);
            } catch (CredentialsConversionException ex) {
                // do not spam the logs with the stacktrace...
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Failed to convert Secret '" + SecretUtils.getCredentialId(s) + "' of type " + type, ex);
                } else {
                    LOG.log(Level.WARNING, "Failed to convert Secret ''{0}'' of type {1} due to {2}", new Object[]{SecretUtils.getCredentialId(s), type, ex.getMessage()});
                }
                return null;
            }
        }
        return null;
    }

    private String getSecretType(Secret s) {
        return s.getType();
    }

    @Override
    public CredentialsStore getStore(ModelObject object) {
        if (object instanceof AbstractFolder || object == Jenkins.getInstance()) {
            return new KubernetesCredentialsStore(this, (ItemGroup) object);
        }
        return null;
    }
}