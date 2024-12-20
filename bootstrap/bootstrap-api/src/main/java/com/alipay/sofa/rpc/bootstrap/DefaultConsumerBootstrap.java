/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.bootstrap;

import com.alipay.sofa.rpc.client.ClientProxyInvoker;
import com.alipay.sofa.rpc.client.Cluster;
import com.alipay.sofa.rpc.client.ClusterFactory;
import com.alipay.sofa.rpc.client.ProviderGroup;
import com.alipay.sofa.rpc.common.RpcConstants;
import com.alipay.sofa.rpc.common.SofaConfigs;
import com.alipay.sofa.rpc.common.SofaOptions;
import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.alipay.sofa.rpc.context.RpcRuntimeContext;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.dynamic.ConfigChangedEvent;
import com.alipay.sofa.rpc.dynamic.ConfigChangeType;
import com.alipay.sofa.rpc.dynamic.DynamicConfigKeys;
import com.alipay.sofa.rpc.dynamic.DynamicConfigManager;
import com.alipay.sofa.rpc.dynamic.DynamicConfigManagerFactory;
import com.alipay.sofa.rpc.dynamic.DynamicUrl;
import com.alipay.sofa.rpc.ext.Extension;
import com.alipay.sofa.rpc.invoke.Invoker;
import com.alipay.sofa.rpc.listener.ConfigListener;
import com.alipay.sofa.rpc.listener.ProviderInfoListener;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.proxy.ProxyFactory;
import com.alipay.sofa.rpc.registry.Registry;
import com.alipay.sofa.rpc.registry.RegistryFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alipay.sofa.rpc.common.RpcConstants.REGISTRY_PROTOCOL_DOMAIN;
import static com.alipay.sofa.common.config.SofaConfigs.getOrDefault;

/**
 * Default consumer bootstrap.
 *
 * @author <a href="mailto:zhanggeng.zg@antfin.com">GengZhang</a>
 */
@Extension("sofa")
public class DefaultConsumerBootstrap<T> extends ConsumerBootstrap<T> {

    /**
     * slf4j Logger for this class
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(DefaultConsumerBootstrap.class);

    /**
     * 构造函数
     *
     * @param consumerConfig 服务消费者配置
     */
    protected DefaultConsumerBootstrap(ConsumerConfig<T> consumerConfig) {
        super(consumerConfig);
    }

    /**
     * 代理实现类
     */
    protected transient volatile T                              proxyIns;

    /**
     * 代理的Invoker对象
     */
    protected transient volatile Invoker                        proxyInvoker;

    /**
     * 调用类
     */
    protected transient volatile Cluster                        cluster;

    /**
     * 计数器
     */
    protected transient volatile CountDownLatch                 respondRegistries;

    /**
     * 发布的调用者配置（含计数器）
     */
    protected final static ConcurrentMap<String, AtomicInteger> REFERRED_KEYS = new ConcurrentHashMap<String, AtomicInteger>();

    @Override
    public T refer() {
        if (proxyIns != null) {
            return proxyIns;
        }
        synchronized (this) {
            if (proxyIns != null) {
                return proxyIns;
            }
            String key = consumerConfig.buildKey();
            String appName = consumerConfig.getAppName();
            // 检查参数
            checkParameters();
            // 提前检查接口类
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, "Refer consumer config : {} with bean id {}", key, consumerConfig.getId());
            }

            // 注意同一interface，同一tags，同一protocol情况
            AtomicInteger cnt = REFERRED_KEYS.get(key); // 计数器
            if (cnt == null) { // 没有发布过
                cnt = CommonUtils.putToConcurrentMap(REFERRED_KEYS, key, new AtomicInteger(0));
            }
            int c = cnt.incrementAndGet();
            int maxProxyCount = consumerConfig.getRepeatedReferLimit();
            if (maxProxyCount > 0) {
                if (c > maxProxyCount) {
                    cnt.decrementAndGet();
                    // 超过最大数量，直接抛出异常
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_DUPLICATE_CONSUMER_CONFIG, key,
                        maxProxyCount));
                } else if (c > 1) {
                    if (LOGGER.isInfoEnabled(appName)) {
                        LOGGER.infoWithApp(appName, "Duplicate consumer config with key {} has been referred!"
                            + " Maybe it's wrong config, please check it."
                            + " Ignore this if you did that on purpose!", key);
                    }
                }
            }

            try {
                // build cluster
                cluster = ClusterFactory.getCluster(this);
                // build listeners
                ConfigListener configListener = buildConfigListener(this);
                consumerConfig.setConfigListener(configListener);
                consumerConfig.setProviderInfoListener(buildProviderInfoListener(this));
                // init cluster
                cluster.init();
                // 构造Invoker对象（执行链）
                proxyInvoker = buildClientProxyInvoker(this);
                // 创建代理类
                proxyIns = (T) ProxyFactory.buildProxy(consumerConfig.getProxy(), consumerConfig.getProxyClass(),
                    proxyInvoker);

                //请求级别动态配置参数
                final String dynamicAlias = consumerConfig.getParameter(DynamicConfigKeys.DYNAMIC_ALIAS);
                if (StringUtils.isNotBlank(dynamicAlias)) {
                    final DynamicConfigManager dynamicManager = DynamicConfigManagerFactory.getDynamicManager(
                            consumerConfig.getAppName(), dynamicAlias);
                    dynamicManager.initServiceConfiguration(consumerConfig.getInterfaceId());
                }

                //接口级别动态配置参数
                Boolean dynamicConfigRefreshEnable = getOrDefault(DynamicConfigKeys.DYNAMIC_REFRESH_ENABLE);
                String configCenterAddress = getOrDefault(DynamicConfigKeys.CONFIG_CENTER_ADDRESS);
                if (dynamicConfigRefreshEnable && StringUtils.isNotBlank(configCenterAddress)) {
                    DynamicUrl dynamicUrl = new DynamicUrl(configCenterAddress);
                    //启用接口级别动态配置
                    final DynamicConfigManager dynamicManager = DynamicConfigManagerFactory.getDynamicManager(
                            consumerConfig.getAppName(), dynamicUrl.getProtocol());
                    dynamicManager.addListener(consumerConfig.getInterfaceId(), configListener);
                    dynamicManager.initServiceConfiguration(consumerConfig.getInterfaceId(), configListener);
                }
            } catch (Exception e) {
                if (cluster != null) {
                    cluster.destroy();
                    cluster = null;
                }
                consumerConfig.setConfigListener(null);
                consumerConfig.setProviderInfoListener(null);
                cnt.decrementAndGet(); // 发布失败不计数
                if (e instanceof SofaRpcRuntimeException) {
                    throw (SofaRpcRuntimeException) e;
                } else {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_BUILD_CONSUMER_PROXY), e);
                }
            }
            if (consumerConfig.getOnAvailable() != null && cluster != null) {
                cluster.checkStateChange(false); // 状态变化通知监听器
            }
            RpcRuntimeContext.cacheConsumerConfig(this);
            return proxyIns;
        }
    }

    /**
     * for check fields and parameters of consumer config
     */
    protected void checkParameters() {

    }

    /**
     * Build ConfigListener for consumer bootstrap.
     *
     * @param bootstrap ConsumerBootstrap
     * @return ConfigListener
     */
    protected ConfigListener buildConfigListener(ConsumerBootstrap bootstrap) {
        return new ConsumerAttributeListener();
    }

    /**
     * Build ProviderInfoListener for consumer bootstrap.
     *
     * @param bootstrap ConsumerBootstrap
     * @return ProviderInfoListener
     */
    protected ProviderInfoListener buildProviderInfoListener(ConsumerBootstrap bootstrap) {
        return new ClusterProviderInfoListener(bootstrap.getCluster());
    }

    /**
     * Build ClientProxyInvoker for consumer bootstrap.
     *
     * @param bootstrap ConsumerBootstrap
     * @return ClientProxyInvoker
     */
    protected ClientProxyInvoker buildClientProxyInvoker(ConsumerBootstrap bootstrap) {
        return new DefaultClientProxyInvoker(bootstrap);
    }

    @Override
    public void unRefer() {
        if (proxyIns == null) {
            return;
        }
        String key = consumerConfig.buildKey();
        String appName = consumerConfig.getAppName();
        if (LOGGER.isInfoEnabled(appName)) {
            LOGGER.infoWithApp(appName, "UnRefer consumer config : {} with bean id {}", key, consumerConfig.getId());
        }
        try {
            cluster.destroy();
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled(appName)) {
                LOGGER.warnWithApp(appName, "Catch exception when unrefer consumer config : " + key
                    + ", but you can ignore if it's called by JVM shutdown hook", e);
            }
        }
        // 清除一些缓存
        AtomicInteger cnt = REFERRED_KEYS.get(key);
        if (cnt != null && cnt.decrementAndGet() <= 0) {
            REFERRED_KEYS.remove(key);
        }
        consumerConfig.setConfigListener(null);
        consumerConfig.setProviderInfoListener(null);
        RpcRuntimeContext.invalidateConsumerConfig(this);
        proxyIns = null;

        // 取消订阅到注册中心
        unSubscribe();
    }

    @Override
    public List<ProviderGroup> subscribe() {
        List<ProviderGroup> result = null;
        String directUrl = consumerConfig.getDirectUrl();
        if (StringUtils.isNotEmpty(directUrl)) {
            // 如果走直连,只保留直连注册中心
            List<RegistryConfig> registryConfigs = new ArrayList<>();
            registryConfigs.add(new RegistryConfig().setProtocol(REGISTRY_PROTOCOL_DOMAIN));
            consumerConfig.setRegistry(registryConfigs);
        }
        List<RegistryConfig> registryConfigs = consumerConfig.getRegistry();
        if (CommonUtils.isNotEmpty(registryConfigs)) {
            // 从多个注册中心订阅服务列表
            result = subscribeFromRegistries();
        }
        return result;
    }

    @Override
    public boolean isSubscribed() {
        return respondRegistries == null || respondRegistries.getCount() <= 0;
    }

    /**
     * Subscribe provider list from all registries, the providers will be merged.
     *
     * @return Provider group list
     */
    protected List<ProviderGroup> subscribeFromRegistries() {
        List<ProviderGroup> result = new ArrayList<ProviderGroup>();
        List<RegistryConfig> registryConfigs = consumerConfig.getRegistry();
        if (CommonUtils.isEmpty(registryConfigs)) {
            return result;
        }
        // 是否等待结果
        int addressWaitTime = consumerConfig.getAddressWait();
        int maxAddressWaitTime = SofaConfigs.getIntegerValue(consumerConfig.getAppName(),
            SofaOptions.CONFIG_MAX_ADDRESS_WAIT_TIME, SofaOptions.MAX_ADDRESS_WAIT_TIME);
        addressWaitTime = addressWaitTime < 0 ? maxAddressWaitTime : Math.min(addressWaitTime, maxAddressWaitTime);

        ProviderInfoListener listener = consumerConfig.getProviderInfoListener();
        respondRegistries = addressWaitTime == 0 ? null : new CountDownLatch(registryConfigs.size());

        // 从注册中心订阅 {groupName: ProviderGroup}
        Map<String, ProviderGroup> tmpProviderInfoList = new HashMap<String, ProviderGroup>();
        for (RegistryConfig registryConfig : registryConfigs) {
            Registry registry = RegistryFactory.getRegistry(registryConfig);
            registry.init();
            registry.start();

            try {
                List<ProviderGroup> current;
                try {
                    if (respondRegistries != null) {
                        consumerConfig.setProviderInfoListener(new WrapperClusterProviderInfoListener(listener,
                            respondRegistries));
                    }
                    current = registry.subscribe(consumerConfig);
                } finally {
                    if (respondRegistries != null) {
                        consumerConfig.setProviderInfoListener(listener);
                    }
                }
                if (current == null) {
                    continue; // 未同步返回结果
                } else {
                    if (respondRegistries != null) {
                        respondRegistries.countDown();
                    }
                }
                for (ProviderGroup group : current) { //  当前注册中心的
                    String groupName = group.getName();
                    if (!group.isEmpty()) {
                        ProviderGroup oldGroup = tmpProviderInfoList.get(groupName);
                        if (oldGroup != null) {
                            oldGroup.addAll(group.getProviderInfos());
                        } else {
                            tmpProviderInfoList.put(groupName, group);
                        }
                    }
                }
            } catch (SofaRpcRuntimeException e) {
                throw e;
            } catch (Throwable e) {
                String appName = consumerConfig.getAppName();
                if (LOGGER.isWarnEnabled(appName)) {
                    LOGGER.warnWithApp(appName,
                        LogCodes.getLog(LogCodes.ERROR_SUBSCRIBE_FROM_REGISTRY, registryConfig.getId()), e);
                }
            }
        }
        if (respondRegistries != null) {
            try {
                respondRegistries.await(addressWaitTime, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) { // NOPMD
            }
        }
        return new ArrayList<ProviderGroup>(tmpProviderInfoList.values());
    }

    /**
     * 取消订阅服务列表
     */
    public void unSubscribe() {
        if (consumerConfig.isSubscribe()) {
            List<RegistryConfig> registryConfigs = consumerConfig.getRegistry();
            if (registryConfigs != null) {
                for (RegistryConfig registryConfig : registryConfigs) {
                    Registry registry = RegistryFactory.getRegistry(registryConfig);
                    try {
                        registry.unSubscribe(consumerConfig);
                    } catch (Exception e) {
                        String appName = consumerConfig.getAppName();
                        if (LOGGER.isWarnEnabled(appName)) {
                            LOGGER.warnWithApp(appName,
                                "Catch exception when unSubscribe from registry: " + registryConfig.getId()
                                    + ", but you can ignore if it's called by JVM shutdown hook", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Wrapper provider info listener to record the respond status of registry.
     */
    class WrapperClusterProviderInfoListener implements ProviderInfoListener {

        /**
         * Origin provider info listener
         */
        private ProviderInfoListener providerInfoListener;
        /**
         * CountDownLatch of respond registries.
         */
        private CountDownLatch       respondRegistries;
        /**
         * Has been respond
         */
        private AtomicBoolean        hasRespond = new AtomicBoolean(false);

        public WrapperClusterProviderInfoListener(ProviderInfoListener providerInfoListener,
                                                  CountDownLatch respondRegistries) {
            this.providerInfoListener = providerInfoListener;
            this.respondRegistries = respondRegistries;
        }

        private void doCountDown() {
            if (respondRegistries != null && hasRespond.compareAndSet(false, true)) {
                respondRegistries.countDown();
                respondRegistries = null;
            }
        }

        @Override
        public void addProvider(ProviderGroup group) {
            providerInfoListener.addProvider(group);
            doCountDown();
        }

        @Override
        public void removeProvider(ProviderGroup group) {
            providerInfoListener.removeProvider(group);
        }

        @Override
        public void updateProviders(ProviderGroup group) {
            providerInfoListener.updateProviders(group);
            doCountDown();
        }

        @Override
        public void updateAllProviders(List<ProviderGroup> groups) {
            providerInfoListener.updateAllProviders(groups);
            doCountDown();
        }
    }

    /**
     * Consumer配置发生变化监听器
     */
    private class ConsumerAttributeListener implements ConfigListener {

        // 可以动态配置的选项
        private final Set<String> supportDynamicConfigKeys = new HashSet<>();
        private final Map<String, String> newValueMap = new HashMap<>();

        ConsumerAttributeListener() {
            supportDynamicConfigKeys.add(RpcConstants.CONFIG_KEY_TIMEOUT);
            supportDynamicConfigKeys.add(RpcConstants.CONFIG_KEY_RETRIES);
            supportDynamicConfigKeys.add(RpcConstants.CONFIG_KEY_LOADBALANCER);
        }

        @Override
        public void process(ConfigChangedEvent event) {
            // 清除上次的动态配置值缓存
            consumerConfig.getDynamicConfigValueCache().clear();
            // 获取对应配置项的默认值
            for (String key : newValueMap.keySet()) {
                if (consumerConfig.getConfigValueCache().get(key) != null) {
                    newValueMap.put(key, String.valueOf(consumerConfig.getConfigValueCache().get(key)));
                } else {
                    newValueMap.put(key, null);
                }
            }
            if (!event.getChangeType().equals(ConfigChangeType.DELETED)) {
                // ADDED or MODIFIED
                Map<String, String> dynamicValueMap = event.getDynamicValueMap();
                for (String key : dynamicValueMap.keySet()) {
                    String tempKey = key.lastIndexOf(".") == -1 ? key : key.substring(key.lastIndexOf(".") + 1);
                    if (supportDynamicConfigKeys.contains(tempKey)) {
                        String value = dynamicValueMap.get(key);
                        if (StringUtils.isNotBlank(value)) {
                            consumerConfig.getDynamicConfigValueCache().put(key, value);
                            newValueMap.put(key, value);
                        }
                    }
                }
            }
            attrUpdated(newValueMap);
        }

        @Override
        public void configChanged(Map newValueMap) {

        }

        @Override
        public synchronized void attrUpdated(Map newValueMap) {
            String appName = consumerConfig.getAppName();
            // 重要： proxyIns不能换，只能换cluster。。。。
            // 修改调用的tags cluster(loadblance) timeout, retries？
            Map<String, String> newValues = (Map<String, String>) newValueMap;
            Map<String, String> oldValues = new HashMap<String, String>();
            boolean rerefer = false;
            try { // 检查是否有变化
                // 是否过滤map?
                for (Map.Entry<String, String> entry : newValues.entrySet()) {
                    String newValue = entry.getValue();
                    String oldValue = consumerConfig.queryAttribute(entry.getKey());
                    boolean changed = oldValue == null ? newValue != null : !oldValue.equals(newValue);
                    if (changed) { // 记住旧的值
                        oldValues.put(entry.getKey(), oldValue);
                    }
                    rerefer = rerefer || changed;
                }
            } catch (Exception e) {
                LOGGER.errorWithApp(appName, LogCodes.getLog(LogCodes.ERROR_CONSUMER_ATTRIBUTE_COMPARING), e);
                return;
            }
            if (rerefer) {
                try {
                    unSubscribe();// 取消订阅旧的
                    for (Map.Entry<String, String> entry : newValues.entrySet()) { // change attrs
                        consumerConfig.updateAttribute(entry.getKey(), entry.getValue(), true);
                    }
                    // 需要重新发布
                    if (LOGGER.isInfoEnabled(appName)) {
                        LOGGER.infoWithApp(appName, "Rerefer consumer {}", consumerConfig.buildKey());
                    }
                } catch (Exception e) { // 切换属性出现异常
                    LOGGER.errorWithApp(appName, LogCodes.getLog(LogCodes.ERROR_CONSUMER_ATTRIBUTE_CHANGE), e);
                    for (Map.Entry<String, String> entry : oldValues.entrySet()) { //rollback old attrs
                        consumerConfig.updateAttribute(entry.getKey(), entry.getValue(), true);
                    }
                    subscribe(); // 重新订阅回滚后的旧的
                    return;
                }
                try {
                    switchCluster();
                } catch (Exception e) { //切换客户端出现异常
                    LOGGER.errorWithApp(appName, LogCodes.getLog(LogCodes.ERROR_CONSUMER_REFER_AFTER_CHANGE), e);
                    unSubscribe(); // 取消订阅新的
                    for (Map.Entry<String, String> entry : oldValues.entrySet()) { //rollback old attrs
                        consumerConfig.updateAttribute(entry.getKey(), entry.getValue(), true);
                    }
                    subscribe(); // 重新订阅回滚后的旧的
                }
            }
        }

        /**
         * Switch cluster.
         *
         * @throws Exception the exception
         */
        private void switchCluster() throws Exception {
            Cluster newCluster = null;
            Cluster oldCluster;
            try { // 构建新的
                newCluster = ClusterFactory.getCluster(DefaultConsumerBootstrap.this); //生成新的 会再重新订阅
                newCluster.init();
                oldCluster = ((ClientProxyInvoker) proxyInvoker).setCluster(newCluster);
            } catch (Exception e) {
                if (newCluster != null) {
                    newCluster.destroy();
                }
                if (e instanceof SofaRpcRuntimeException) {
                    throw e;
                } else {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_SWITCH_CLUSTER_NEW), e);
                }
            }
            try { // 切换
                cluster = newCluster;
                if (oldCluster != null) {
                    oldCluster.destroy(); // 旧的关掉
                }
            } catch (Exception e) {
                String appName = consumerConfig.getAppName();
                if (LOGGER.isWarnEnabled(appName)) {
                    LOGGER.warnWithApp(appName, LogCodes.getLog(LogCodes.WARN_SWITCH_CLUSTER_DESTROY), e);
                }
            }
        }
    }

    @Override
    public Cluster getCluster() {
        return cluster;
    }

    @Override
    public T getProxyIns() {
        return proxyIns;
    }

    /**
     * 得到实现代理类Invoker
     *
     * @return 实现代理类Invoker proxy invoker
     */
    public Invoker getProxyInvoker() {
        return proxyInvoker;
    }
}
