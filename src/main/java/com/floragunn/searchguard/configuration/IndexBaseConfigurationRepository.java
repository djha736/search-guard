/*
 * Copyright 2015-2017 floragunn GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.floragunn.searchguard.configuration;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.LifecycleListener;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.auditlog.AuditLog;
import com.floragunn.searchguard.compliance.ComplianceConfig;
import com.floragunn.searchguard.ssl.util.ExceptionUtils;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.support.ConfigHelper;
import com.floragunn.searchguard.support.LicenseHelper;
import com.floragunn.searchguard.support.SgUtils;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

public class IndexBaseConfigurationRepository implements ConfigurationRepository {
    private static final Logger LOGGER = LogManager.getLogger(IndexBaseConfigurationRepository.class);
    private static final Pattern DLS_PATTERN = Pattern.compile(".+\\.indices\\..+\\._dls_=.+", Pattern.DOTALL);
    private static final Pattern FLS_PATTERN = Pattern.compile(".+\\.indices\\..+\\._fls_\\.[0-9]+=.+", Pattern.DOTALL);

    private final String searchguardIndex;
    private final Client client;
    private final ConcurrentMap<String, Settings> typeToConfig;
    private final Multimap<String, ConfigurationChangeListener> configTypeToChancheListener;
    private final List<LicenseChangeListener> licenseChangeListener;
    private final ConfigurationLoader cl;
    private final LegacyConfigurationLoader legacycl;
    private final Settings settings;
    private final ClusterService clusterService;
    private final AuditLog auditLog;
    private final ComplianceConfig complianceConfig;
    private ThreadPool threadPool;
    private volatile SearchGuardLicense effectiveLicense;

    private IndexBaseConfigurationRepository(Settings settings, final Path configPath, ThreadPool threadPool, Client client,
            ClusterService clusterService, AuditLog auditLog, ComplianceConfig complianceConfig) {
        this.searchguardIndex = settings.get(ConfigConstants.SEARCHGUARD_CONFIG_INDEX_NAME, ConfigConstants.SG_DEFAULT_CONFIG_INDEX);
        this.settings = settings;
        this.client = client;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.auditLog = auditLog;
        this.complianceConfig = complianceConfig;
        this.typeToConfig = Maps.newConcurrentMap();
        this.configTypeToChancheListener = ArrayListMultimap.create();
        this.licenseChangeListener = new ArrayList<LicenseChangeListener>();
        cl = new ConfigurationLoader(client, threadPool, settings);
        legacycl = new LegacyConfigurationLoader(client, threadPool, settings);

        final AtomicBoolean installDefaultConfig = new AtomicBoolean();

        clusterService.addLifecycleListener(new LifecycleListener() {

            @Override
            public void afterStart() {

                final Thread bgThread = new Thread(new Runnable() {

                    @Override
                    public void run() {
                        try {

                            if (installDefaultConfig.get()) {

                                try {
                                    String lookupDir = System.getProperty("sg.default_init.dir");
                                    final String cd = lookupDir != null ? (lookupDir + "/")
                                            : new Environment(settings, configPath).pluginsFile().toAbsolutePath().toString()
                                                    + "/search-guard-6/sgconfig/";
                                    File confFile = new File(cd + "sg_config.yml");
                                    if (confFile.exists()) {
                                        final ThreadContext threadContext = threadPool.getThreadContext();
                                        try (StoredContext ctx = threadContext.stashContext()) {
                                            threadContext.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");
                                            LOGGER.info("Will create {} index so we can apply default config", searchguardIndex);

                                            Map<String, Object> indexSettings = new HashMap<>();
                                            indexSettings.put("index.number_of_shards", 1);
                                            indexSettings.put("index.auto_expand_replicas", "0-all");

                                            boolean ok = client.admin().indices()
                                                    .create(new CreateIndexRequest(searchguardIndex).settings(indexSettings)).actionGet()
                                                    .isAcknowledged();
                                            if (ok) {
                                                ConfigHelper.uploadFile(client, cd + "sg_config.yml", searchguardIndex, "config");
                                                ConfigHelper.uploadFile(client, cd + "sg_roles.yml", searchguardIndex, "roles");
                                                ConfigHelper.uploadFile(client, cd + "sg_roles_mapping.yml", searchguardIndex, "rolesmapping");
                                                ConfigHelper.uploadFile(client, cd + "sg_internal_users.yml", searchguardIndex, "internalusers");
                                                ConfigHelper.uploadFile(client, cd + "sg_action_groups.yml", searchguardIndex, "actiongroups");
                                                LOGGER.info("Default config applied");
                                            }
                                        }
                                    } else {
                                        LOGGER.error("{} does not exist", confFile.getAbsolutePath());
                                    }
                                } catch (Exception e) {
                                    LOGGER.debug("Cannot apply default config (this is not an error!) due to {}", e.getMessage());
                                }
                            }

                            LOGGER.debug("Node started, try to initialize it. Wait for at least yellow cluster state....");
                            ClusterHealthResponse response = null;
                            try {
                                response = client.admin().cluster().health(new ClusterHealthRequest(searchguardIndex).waitForYellowStatus())
                                        .actionGet();
                            } catch (Exception e1) {
                                LOGGER.debug("Catched a {} but we just try again ...", e1.toString());
                            }

                            while (response == null || response.isTimedOut() || response.getStatus() == ClusterHealthStatus.RED) {
                                LOGGER.debug("index '{}' not healthy yet, we try again ... (Reason: {})", searchguardIndex,
                                        response == null ? "no response" : (response.isTimedOut() ? "timeout" : "other, maybe red cluster"));
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e1) {
                                    //ignore
                                    Thread.currentThread().interrupt();
                                }
                                try {
                                    response = client.admin().cluster().health(new ClusterHealthRequest(searchguardIndex).waitForYellowStatus())
                                            .actionGet();
                                } catch (Exception e1) {
                                    LOGGER.debug("Catched again a {} but we just try again ...", e1.toString());
                                }
                                continue;
                            }

                            while (true) {
                                try {
                                    LOGGER.debug("Try to load config ...");
                                    reloadConfiguration(
                                            Arrays.asList(new String[] { "config", "roles", "rolesmapping", "internalusers", "actiongroups" }));
                                    break;
                                } catch (Exception e) {
                                    LOGGER.debug("Unable to load configuration due to {}", String.valueOf(ExceptionUtils.getRootCause(e)));
                                    try {
                                        Thread.sleep(3000);
                                    } catch (InterruptedException e1) {
                                        Thread.currentThread().interrupt();
                                        LOGGER.debug("Thread was interrupted so we cancel initialization");
                                        break;
                                    }
                                }
                            }

                            LOGGER.info("Node '{}' initialized", clusterService.localNode().getName());

                        } catch (Exception e) {
                            LOGGER.error("Unexpected exception while initializing node " + e, e);
                        }
                    }
                });

                LOGGER.info("Check if " + searchguardIndex + " index exists ...");

                try {

                    IndicesExistsRequest ier = new IndicesExistsRequest(searchguardIndex).masterNodeTimeout(TimeValue.timeValueMinutes(1));

                    final ThreadContext threadContext = threadPool.getThreadContext();

                    try (StoredContext ctx = threadContext.stashContext()) {
                        threadContext.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");

                        client.admin().indices().exists(ier, new ActionListener<IndicesExistsResponse>() {

                            @Override
                            public void onResponse(IndicesExistsResponse response) {
                                if (response != null && response.isExists()) {
                                    bgThread.start();
                                } else {
                                    if (settings.get("tribe.name", null) == null && settings.getByPrefix("tribe").size() > 0) {
                                        LOGGER.info(
                                                "{} index does not exist yet, but we are a tribe node. So we will load the config anyhow until we got it ...",
                                                searchguardIndex);
                                        bgThread.start();
                                    } else {

                                        if (settings.getAsBoolean(ConfigConstants.SEARCHGUARD_ALLOW_DEFAULT_INIT_SGINDEX, false)) {
                                            LOGGER.info("{} index does not exist yet, so we create a default config", searchguardIndex);
                                            installDefaultConfig.set(true);
                                            bgThread.start();
                                        } else {
                                            LOGGER.info(
                                                    "{} index does not exist yet, so no need to load config on node startup. Use sgadmin to initialize cluster",
                                                    searchguardIndex);
                                        }
                                    }
                                }
                            }

                            @Override
                            public void onFailure(Exception e) {
                                LOGGER.error("Failure while checking {} index {}", e, searchguardIndex, e);
                                bgThread.start();
                            }
                        });
                    }
                } catch (Throwable e2) {
                    LOGGER.error("Failure while executing IndicesExistsRequest {}", e2, e2);
                    bgThread.start();
                }
            }
        });
    }

    public static ConfigurationRepository create(Settings settings, final Path configPath, final ThreadPool threadPool, Client client,
            ClusterService clusterService, AuditLog auditLog, ComplianceConfig complianceConfig) {
        final IndexBaseConfigurationRepository repository = new IndexBaseConfigurationRepository(settings, configPath, threadPool, client,
                clusterService, auditLog, complianceConfig);
        return repository;
    }

    @Override
    public Settings getConfiguration(String configurationType) {

        Settings result = typeToConfig.get(configurationType);

        if (result != null) {
            return result;
        }

        Map<String, Tuple<Long, Settings>> loaded = loadConfigurations(Collections.singleton(configurationType), false);

        result = loaded.get(configurationType).v2();

        return putSettingsToCache(configurationType, result);
    }

    private Settings putSettingsToCache(String configurationType, Settings result) {
        if (result != null) {
            typeToConfig.putIfAbsent(configurationType, result);
        }

        return typeToConfig.get(configurationType);
    }

    @Override
    public Map<String, Settings> reloadConfiguration(Collection<String> configTypes) {
        Map<String, Tuple<Long, Settings>> loaded = loadConfigurations(configTypes, false);
        Map<String, Settings> loaded0 = loaded.entrySet().stream().collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue().v2()));
        typeToConfig.keySet().removeAll(loaded0.keySet());
        typeToConfig.putAll(loaded0);
        notifyAboutChanges(loaded0);

        final SearchGuardLicense sgLicense = getLicense();

        notifyAboutLicenseChanges(sgLicense);

        final String license = sgLicense == null ? "No license needed because enterprise modules are not enabled" : sgLicense.toString();
        LOGGER.info("Search Guard License Info: " + license);

        if (sgLicense != null) {
            LOGGER.info("Search Guard License Type: " + sgLicense.getType() + ", " + (sgLicense.isValid() ? "valid" : "invalid"));

            if (sgLicense.getExpiresInDays() <= 30 && sgLicense.isValid()) {
                LOGGER.warn("Your Search Guard license expires in " + sgLicense.getExpiresInDays() + " days.");
                System.out.println("Your Search Guard license expires in " + sgLicense.getExpiresInDays() + " days.");
            }

            if (!sgLicense.isValid()) {
                final String reasons = String.join("; ", sgLicense.getMsgs());
                LOGGER.error("You are running an unlicensed version of Search Guard. Reason(s): " + reasons);
                System.out.println("You are running an unlicensed version of Search Guard. Reason(s): " + reasons);
                System.err.println("You are running an unlicensed version of Search Guard. Reason(s): " + reasons);
            }
        }

        return loaded0;
    }

    @Override
    public void persistConfiguration(String configurationType, Settings settings) {
        //TODO should be use from com.floragunn.searchguard.tools.SearchGuardAdmin
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public synchronized void subscribeOnChange(String configurationType, ConfigurationChangeListener listener) {
        LOGGER.debug("Subscribe on configuration changes by type {} with listener {}", configurationType, listener);
        configTypeToChancheListener.put(configurationType, listener);
    }

    //@Override
    public synchronized void subscribeOnLicenseChange(LicenseChangeListener licenseChangeListener) {
        if (licenseChangeListener != null) {
            this.licenseChangeListener.add(licenseChangeListener);
        }
    }

    private synchronized void notifyAboutLicenseChanges(SearchGuardLicense license) {
        for (LicenseChangeListener listener : this.licenseChangeListener) {
            listener.onChange(license);
        }
    }

    private synchronized void notifyAboutChanges(Map<String, Settings> typeToConfig) {
        for (Map.Entry<String, ConfigurationChangeListener> entry : configTypeToChancheListener.entries()) {
            String type = entry.getKey();
            ConfigurationChangeListener listener = entry.getValue();

            Settings settings = typeToConfig.get(type);

            if (settings == null) {
                continue;
            }

            try {
                LOGGER.debug("Notify {} listener about change configuration with type {}", listener, type);
                final long start = LOGGER.isDebugEnabled() ? System.currentTimeMillis() : 0L;
                listener.onChange(settings);
                LOGGER.debug("listener {} notified about type {} in {} ms", listener, type, (System.currentTimeMillis() - start));
            } catch (Exception e) {
                LOGGER.error("{} listener errored: " + e, listener, e);
                throw ExceptionsHelper.convertToElastic(e);
            }
        }
    }

    public Map<String, Tuple<Long, Settings>> loadConfigurations(Collection<String> configTypes, boolean logComplianceEvent) {

        final ThreadContext threadContext = threadPool.getThreadContext();
        final Map<String, Tuple<Long, Settings>> retVal = new HashMap<String, Tuple<Long, Settings>>();

        try (StoredContext ctx = threadContext.stashContext()) {
            threadContext.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");

            boolean searchGuardIndexExists = clusterService.state().metaData().hasConcreteIndex(this.searchguardIndex);

            if (searchGuardIndexExists) {
                if (clusterService.state().metaData().index(this.searchguardIndex).mapping("config") != null) {
                    //legacy layout
                    LOGGER.debug("sg index exists and was created before ES 6 (legacy layout)");
                    retVal.putAll(validate(legacycl.loadLegacy(configTypes.toArray(new String[0]), 5, TimeUnit.SECONDS), configTypes.size()));
                } else {
                    LOGGER.debug("sg index exists and was created with ES 6 (new layout)");
                    retVal.putAll(validate(cl.load(configTypes.toArray(new String[0]), 5, TimeUnit.SECONDS), configTypes.size()));
                }
            } else {
                //wait (and use new layout)
                LOGGER.debug("sg index not exists (yet)");
                retVal.putAll(validate(cl.load(configTypes.toArray(new String[0]), 30, TimeUnit.SECONDS), configTypes.size()));
            }

        } catch (Exception e) {
            throw new ElasticsearchException(e);
        }

        if (logComplianceEvent && complianceConfig.isEnabled()) {
            String configurationType = configTypes.iterator().next();
            Map<String, String> fields = new HashMap<String, String>();
            fields.put(configurationType, Strings.toString(retVal.get(configurationType).v2()));
            auditLog.logDocumentRead(this.searchguardIndex, configurationType, null, fields, complianceConfig);
        }

        return retVal;
    }

    private Map<String, Tuple<Long, Settings>> validate(Map<String, Tuple<Long, Settings>> conf, int expectedSize) throws InvalidConfigException {

        if (conf == null || conf.size() != expectedSize) {
            throw new InvalidConfigException("Retrieved only partial configuration");
        }

        final Tuple<Long, Settings> roles = conf.get("roles");
        final String rolesDelimited;

        if (roles != null && roles.v2() != null && (rolesDelimited = roles.v2().toDelimitedString('#')) != null) {

            //<role>.indices.<indice>._dls_= OK
            //<role>.indices.<indice>._fls_.<num>= OK

            final String[] rolesString = rolesDelimited.split("#");

            for (String role : rolesString) {
                if (role.contains("_fls_.") && !FLS_PATTERN.matcher(role).matches()) {
                    LOGGER.error("Invalid FLS configuration detected, FLS/DLS will not work correctly: {}", role);
                }

                if (role.contains("_dls_=") && !DLS_PATTERN.matcher(role).matches()) {
                    LOGGER.error("Invalid DLS configuration detected, FLS/DLS will not work correctly: {}", role);
                }
            }
        }

        return conf;
    }

    private static String formatDate(long date) {
        return new SimpleDateFormat("yyyy-MM-dd", SgUtils.EN_Locale).format(new Date(date));
    }

    /**
     *
     * @return null if no license is needed
     */
    public SearchGuardLicense getLicense() {

        //TODO check spoof with cluster settings and elasticsearch.yml without node restart
        boolean enterpriseModulesEnabled = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_ENTERPRISE_MODULES_ENABLED, true);

        if (!enterpriseModulesEnabled) {
            return null;
        }

        String licenseText = getConfiguration("config").get("searchguard.dynamic.license");

        if (licenseText == null || licenseText.isEmpty()) {
            if (effectiveLicense != null) {
                return effectiveLicense;
            }
            return createOrGetTrial(null);
        } else {
            try {
                licenseText = LicenseHelper.validateLicense(licenseText);
                SearchGuardLicense retVal = new SearchGuardLicense(XContentHelper.convertToMap(XContentType.JSON.xContent(), licenseText, true),
                        clusterService);
                effectiveLicense = retVal;
                return retVal;
            } catch (Exception e) {
                LOGGER.error("Unable to verify license", e);
                if (effectiveLicense != null) {
                    return effectiveLicense;
                }
                return createOrGetTrial("Unable to verify license due to " + ExceptionUtils.getRootCause(e));
            }
        }

    }

    private SearchGuardLicense createOrGetTrial(String msg) {
        long created = System.currentTimeMillis();
        ThreadContext threadContext = threadPool.getThreadContext();

        try (StoredContext ctx = threadContext.stashContext()) {
            threadContext.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");
            GetResponse get = client.prepareGet(searchguardIndex, "sg", "tattr").get();
            if (get.isExists()) {
                created = (long) get.getSource().get("val");
            } else {
                try {
                    client.index(new IndexRequest(searchguardIndex).type("sg").id("tattr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).create(true)
                            .source("{\"val\": " + System.currentTimeMillis() + "}", XContentType.JSON)).actionGet();
                } catch (VersionConflictEngineException e) {
                    //ignore
                } catch (Exception e) {
                    LOGGER.error("Unable to index tattr", e);
                }
            }
        }

        return SearchGuardLicense.createTrialLicense(formatDate(created), clusterService, msg);
    }
}