/*
 * Copyright 2019-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.altindag.ssl.util;

import nl.altindag.ssl.exception.GenericKeyManagerException;
import nl.altindag.ssl.keymanager.CompositeX509ExtendedKeyManager;
import nl.altindag.ssl.keymanager.HotSwappableX509ExtendedKeyManager;
import nl.altindag.ssl.keymanager.KeyManagerFactoryWrapper;
import nl.altindag.ssl.keymanager.X509KeyManagerWrapper;
import nl.altindag.ssl.model.KeyStoreHolder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.URI;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Hakan Altindag
 */
public final class KeyManagerUtils {

    private KeyManagerUtils() {}

    public static X509ExtendedKeyManager combine(X509KeyManager... keyManagers) {
        return combine(Arrays.asList(keyManagers));
    }

    public static X509ExtendedKeyManager combine(List<? extends X509KeyManager> keyManagers) {
        return KeyManagerUtils.keyManagerBuilder()
                .withKeyManagers(keyManagers)
                .build();
    }

    public static <T extends X509KeyManager> X509ExtendedKeyManager[] toArray(T keyManager) {
        return new X509ExtendedKeyManager[]{KeyManagerUtils.wrapIfNeeded(keyManager)};
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStoreHolder... keyStoreHolders) {
        return Arrays.stream(keyStoreHolders)
                .map(keyStoreHolder -> createKeyManager(keyStoreHolder.getKeyStore(), keyStoreHolder.getKeyPassword()))
                .collect(Collectors.collectingAndThen(Collectors.toList(), KeyManagerUtils::combine));
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStore keyStore, char[] keyPassword) {
        return createKeyManager(keyStore, keyPassword, KeyManagerFactory.getDefaultAlgorithm());
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStore keyStore, char[] keyPassword, String keyManagerFactoryAlgorithm) {
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(keyManagerFactoryAlgorithm);
            return createKeyManager(keyStore, keyPassword, keyManagerFactory);
        } catch (NoSuchAlgorithmException e) {
            throw new GenericKeyManagerException(e);
        }
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStore keyStore, char[] keyPassword, String keyManagerFactoryAlgorithm, String securityProviderName) {
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(keyManagerFactoryAlgorithm, securityProviderName);
            return createKeyManager(keyStore, keyPassword, keyManagerFactory);
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new GenericKeyManagerException(e);
        }
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStore keyStore, char[] keyPassword, String keyManagerFactoryAlgorithm, Provider securityProvider) {
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(keyManagerFactoryAlgorithm, securityProvider);
            return createKeyManager(keyStore, keyPassword, keyManagerFactory);
        } catch (NoSuchAlgorithmException e) {
            throw new GenericKeyManagerException(e);
        }
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStore keyStore, char[] keyPassword, KeyManagerFactory keyManagerFactory) {
        try {
            keyManagerFactory.init(keyStore, keyPassword);
            return KeyManagerUtils.getKeyManager(keyManagerFactory);
        } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
            throw new GenericKeyManagerException(e);
        }
    }

    public static X509ExtendedKeyManager createKeyManager(KeyStore keyStore, Map<String, char[]> aliasToPassword) {
        List<X509ExtendedKeyManager> keyManagers = new ArrayList<>();

        for (Entry<String, char[]> entry : aliasToPassword.entrySet()) {
            try {
                String alias = entry.getKey();
                char[] password = entry.getValue();

                if (keyStore.isKeyEntry(alias)) {
                    Key key = keyStore.getKey(alias, password);
                    Certificate[] certificateChain = keyStore.getCertificateChain(alias);

                    KeyStore identityStore = KeyStoreUtils.createIdentityStore(key, password, certificateChain);
                    X509ExtendedKeyManager keyManager = KeyManagerUtils.createKeyManager(identityStore, password);
                    keyManagers.add(keyManager);
                }
            } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
                throw new GenericKeyManagerException(e);
            }
        }

        if (keyManagers.isEmpty()) {
            throw new GenericKeyManagerException("Could not create any KeyManager from the given KeyStore, Alias and Password");
        }

        return KeyManagerUtils.combine(keyManagers);
    }

    public static X509ExtendedKeyManager wrapIfNeeded(X509KeyManager keyManager) {
        if (keyManager instanceof X509ExtendedKeyManager) {
            return (X509ExtendedKeyManager) keyManager;
        } else {
            return new X509KeyManagerWrapper(keyManager);
        }
    }

    public static KeyManagerFactory createKeyManagerFactory(KeyManager keyManager) {
        return new KeyManagerFactoryWrapper(keyManager);
    }

    public static <T extends KeyManagerFactory> X509ExtendedKeyManager getKeyManager(T keyManagerFactory) {
        return Arrays.stream(keyManagerFactory.getKeyManagers())
                .filter(X509KeyManager.class::isInstance)
                .map(X509KeyManager.class::cast)
                .map(KeyManagerUtils::wrapIfNeeded)
                .collect(Collectors.collectingAndThen(Collectors.toList(), KeyManagerUtils::combine));
    }

    /**
     * Wraps the given KeyManager into an instance of a Hot Swappable KeyManager
     * This type of KeyManager has the capability of swapping in and out different KeyManagers at runtime.
     *
     * @param keyManager    To be wrapped KeyManager
     * @return              Swappable KeyManager
     */
    public static X509ExtendedKeyManager createSwappableKeyManager(X509KeyManager keyManager) {
        return new HotSwappableX509ExtendedKeyManager(KeyManagerUtils.wrapIfNeeded(keyManager));
    }

    /**
     * Swaps the internal TrustManager instance with the given keyManager object.
     * The baseKeyManager should be an instance of {@link HotSwappableX509ExtendedKeyManager}
     * and can be created with {@link KeyManagerUtils#createSwappableKeyManager(X509KeyManager)}
     *
     * @param baseKeyManager                an instance of {@link HotSwappableX509ExtendedKeyManager}
     * @param newKeyManager                 to be injected instance of a TrustManager
     * @throws GenericKeyManagerException   if {@code baseKeyManager} is not instance of {@link HotSwappableX509ExtendedKeyManager}
     */
    public static void swapKeyManager(X509KeyManager baseKeyManager, X509KeyManager newKeyManager) {
        if (newKeyManager instanceof HotSwappableX509ExtendedKeyManager) {
            throw new GenericKeyManagerException(
                    String.format("The newKeyManager should not be an instance of [%s]", HotSwappableX509ExtendedKeyManager.class.getName())
            );
        }

        if (baseKeyManager instanceof HotSwappableX509ExtendedKeyManager) {
            ((HotSwappableX509ExtendedKeyManager) baseKeyManager).setKeyManager(KeyManagerUtils.wrapIfNeeded(newKeyManager));
        } else {
            throw new GenericKeyManagerException(
                    String.format("The baseKeyManager is from the instance of [%s] and should be an instance of [%s].",
                            baseKeyManager.getClass().getName(),
                            HotSwappableX509ExtendedKeyManager.class.getName())
            );
        }
    }

    public static void addClientIdentityRoute(X509ExtendedKeyManager keyManager, String clientAlias, String... hosts) {
        addClientIdentityRoute(keyManager, clientAlias, hosts, false);
    }

    public static void overrideClientIdentityRoute(X509ExtendedKeyManager keyManager, String clientAlias, String... hosts) {
        addClientIdentityRoute(keyManager, clientAlias, hosts, true);
    }

    private static void addClientIdentityRoute(X509ExtendedKeyManager keyManager,
                                               String clientAlias,
                                               String[] hosts,
                                               boolean overrideExistingRouteEnabled) {

        Objects.requireNonNull(keyManager);
        Objects.requireNonNull(clientAlias);
        Objects.requireNonNull(hosts);

        if (keyManager instanceof CompositeX509ExtendedKeyManager) {
            CompositeX509ExtendedKeyManager compositeX509ExtendedKeyManager = (CompositeX509ExtendedKeyManager) keyManager;
            Map<String, List<URI>> clientAliasToHosts = compositeX509ExtendedKeyManager.getPreferredClientAliasToHosts();

            List<URI> uris = new ArrayList<>();
            for (String host : hosts) {
                URI uri = URI.create(host);
                UriUtils.validate(uri);
                uris.add(uri);
            }

            if (overrideExistingRouteEnabled && clientAliasToHosts.containsKey(clientAlias)) {
                clientAliasToHosts.get(clientAlias).clear();
            }

            for (URI uri : uris) {
                if (clientAliasToHosts.containsKey(clientAlias)) {
                    clientAliasToHosts.get(clientAlias).add(uri);
                } else {
                    clientAliasToHosts.put(clientAlias, new ArrayList<>(Collections.singleton(uri)));
                }
            }
        } else {
            throw new GenericKeyManagerException(String.format(
                    "KeyManager should be an instance of: [%s], but received: [%s]",
                    CompositeX509ExtendedKeyManager.class.getName(),
                    keyManager.getClass().getName()));
        }
    }

    public static Map<String, List<String>> getClientIdentityRoute(X509ExtendedKeyManager keyManager) {
        Objects.requireNonNull(keyManager);

        if (keyManager instanceof CompositeX509ExtendedKeyManager) {
            return ((CompositeX509ExtendedKeyManager) keyManager)
                    .getPreferredClientAliasToHosts()
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            hosts -> hosts.getValue().stream()
                                    .map(URI::toString)
                                    .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList)))
                    );
        } else {
            throw new GenericKeyManagerException(String.format(
                    "KeyManager should be an instance of: [%s], but received: [%s]",
                    CompositeX509ExtendedKeyManager.class.getName(),
                    keyManager.getClass().getName()));
        }
    }

    private static List<X509ExtendedKeyManager> unwrapIfPossible(X509ExtendedKeyManager keyManager) {
        List<X509ExtendedKeyManager> keyManagers = new ArrayList<>();
        if (keyManager instanceof CompositeX509ExtendedKeyManager) {
            for (X509ExtendedKeyManager innerKeyManager : ((CompositeX509ExtendedKeyManager) keyManager).getKeyManagers()) {
                List<X509ExtendedKeyManager> unwrappedKeyManagers = KeyManagerUtils.unwrapIfPossible(innerKeyManager);
                keyManagers.addAll(unwrappedKeyManagers);
            }
        } else {
            keyManagers.add(keyManager);
        }

        return keyManagers;
    }

    public static KeyManagerBuilder keyManagerBuilder() {
        return new KeyManagerBuilder();
    }

    public static final class KeyManagerBuilder {

        private static final String EMPTY_KEY_MANAGER_EXCEPTION = "Input does not contain KeyManagers";

        private final List<X509ExtendedKeyManager> keyManagers = new ArrayList<>();
        private final Map<String, List<URI>> clientAliasToHost = new HashMap<>();
        private boolean swappableKeyManagerEnabled = false;

        private KeyManagerBuilder() {}

        public <T extends X509KeyManager> KeyManagerBuilder withKeyManagers(T... keyManagers) {
            for (X509KeyManager keyManager : keyManagers) {
                withKeyManager(keyManager);
            }
            return this;
        }

        public <T extends X509KeyManager> KeyManagerBuilder withKeyManagers(List<T> keyManagers) {
            for (X509KeyManager keyManager : keyManagers) {
                withKeyManager(keyManager);
            }
            return this;
        }

        public <T extends X509KeyManager> KeyManagerBuilder withKeyManager(T keyManager) {
            this.keyManagers.add(KeyManagerUtils.wrapIfNeeded(keyManager));
            return this;
        }

        public <T extends KeyStoreHolder> KeyManagerBuilder withIdentities(T... identities) {
            return withIdentities(Arrays.asList(identities));
        }

        public KeyManagerBuilder withIdentities(List<? extends KeyStoreHolder> identities) {
            for (KeyStoreHolder identity : identities) {
                this.keyManagers.add(KeyManagerUtils.createKeyManager(identity.getKeyStore(), identity.getKeyPassword()));
            }
            return this;
        }

        public <T extends KeyStore> KeyManagerBuilder withIdentity(T identity, char[] identityPassword, String keyManagerAlgorithm) {
            this.keyManagers.add(KeyManagerUtils.createKeyManager(identity, identityPassword, keyManagerAlgorithm));
            return this;
        }

        public KeyManagerBuilder withSwappableKeyManager(boolean swappableKeyManagerEnabled) {
            this.swappableKeyManagerEnabled = swappableKeyManagerEnabled;
            return this;
        }

        public KeyManagerBuilder withClientAliasToHost(Map<String, List<URI>> clientAliasToHost) {
            this.clientAliasToHost.putAll(clientAliasToHost);
            return this;
        }

        public X509ExtendedKeyManager build() {
            if (keyManagers.isEmpty()) {
                throw new GenericKeyManagerException(EMPTY_KEY_MANAGER_EXCEPTION);
            }

            X509ExtendedKeyManager keyManager;
            if (keyManagers.size() == 1) {
                keyManager = keyManagers.get(0);
            } else {
                keyManager = keyManagers.stream()
                        .map(KeyManagerUtils::unwrapIfPossible)
                        .flatMap(Collection::stream)
                        .collect(Collectors.collectingAndThen(
                                Collectors.toList(),
                                extendedKeyManagers -> new CompositeX509ExtendedKeyManager(extendedKeyManagers, clientAliasToHost)
                        ));
            }

            if (swappableKeyManagerEnabled) {
                keyManager = KeyManagerUtils.createSwappableKeyManager(keyManager);
            }

            return keyManager;
        }

    }

}
