// Copyright 2017 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.diskstorage.cql;

import static com.datastax.driver.core.schemabuilder.SchemaBuilder.createKeyspace;
import static com.datastax.driver.core.schemabuilder.SchemaBuilder.dropKeyspace;
import static com.datastax.driver.core.querybuilder.QueryBuilder.truncate;
import static io.vavr.API.$;
import static io.vavr.API.Case;
import static io.vavr.API.Match;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.ATOMIC_BATCH_MUTATE;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.BATCH_STATEMENT_SIZE;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.CLUSTER_NAME;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.ONLY_USE_LOCAL_CONSISTENCY_FOR_SYSTEM_OPERATIONS;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.KEYSPACE;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.LOCAL_CORE_CONNECTIONS_PER_HOST;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.LOCAL_DATACENTER;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.LOCAL_MAX_CONNECTIONS_PER_HOST;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.LOCAL_MAX_REQUESTS_PER_CONNECTION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.PROTOCOL_VERSION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.READ_CONSISTENCY;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.REMOTE_CORE_CONNECTIONS_PER_HOST;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.REMOTE_MAX_CONNECTIONS_PER_HOST;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.REMOTE_MAX_REQUESTS_PER_CONNECTION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.REPLICATION_FACTOR;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.REPLICATION_OPTIONS;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.REPLICATION_STRATEGY;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_ENABLED;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_TRUSTSTORE_LOCATION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_TRUSTSTORE_PASSWORD;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_CLIENT_AUTHENTICATION_ENABLED;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_KEYSTORE_LOCATION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_KEYSTORE_KEY_PASSWORD;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_KEYSTORE_STORE_PASSWORD;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.WRITE_CONSISTENCY;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.USE_EXTERNAL_LOCKING;
import static org.janusgraph.diskstorage.cql.CQLKeyColumnValueStore.EXCEPTION_MAPPER;
import static org.janusgraph.diskstorage.cql.CQLTransaction.getTransaction;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.AUTH_PASSWORD;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.AUTH_USERNAME;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.DROP_ON_CLEAR;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.METRICS_JMX_ENABLED;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.METRICS_PREFIX;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.buildGraphConfiguration;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.GRAPH_NAME;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.BASIC_METRICS;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Resource;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.janusgraph.diskstorage.BackendException;
import org.janusgraph.diskstorage.BaseTransactionConfig;
import org.janusgraph.diskstorage.PermanentBackendException;
import org.janusgraph.diskstorage.StaticBuffer;
import org.janusgraph.diskstorage.StoreMetaData.Container;
import org.janusgraph.diskstorage.common.DistributedStoreManager;
import org.janusgraph.diskstorage.configuration.Configuration;
import org.janusgraph.diskstorage.keycolumnvalue.KCVMutation;
import org.janusgraph.diskstorage.keycolumnvalue.KeyColumnValueStore;
import org.janusgraph.diskstorage.keycolumnvalue.KeyColumnValueStoreManager;
import org.janusgraph.diskstorage.keycolumnvalue.KeyRange;
import org.janusgraph.diskstorage.keycolumnvalue.StandardStoreFeatures;
import org.janusgraph.diskstorage.keycolumnvalue.StoreFeatures;
import org.janusgraph.diskstorage.keycolumnvalue.StoreTransaction;
import org.janusgraph.hadoop.CqlHadoopStoreManager;
import org.janusgraph.util.stats.MetricManager;
import org.janusgraph.util.system.NetworkUtil;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BatchStatement.Type;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Cluster.Builder;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.JdkSSLOptions;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.vavr.Tuple;
import io.vavr.collection.Array;
import io.vavr.collection.HashMap;
import io.vavr.collection.Iterator;
import io.vavr.collection.Seq;
import io.vavr.concurrent.Future;
import io.vavr.control.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class creates see {@link CQLKeyColumnValueStore CQLKeyColumnValueStores} and handles Cassandra-backed allocation of vertex IDs for JanusGraph (when so
 * configured).
 */
public class CQLStoreManager extends DistributedStoreManager implements KeyColumnValueStoreManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CQLStoreManager.class);

    static final String CONSISTENCY_LOCAL_QUORUM = "LOCAL_QUORUM";
    static final String CONSISTENCY_QUORUM = "QUORUM";

    private static final int DEFAULT_PORT = 9042;  //直接给定了默认端口，Cassandra

    private final String keyspace;
    private final int batchSize;
    private final boolean atomicBatch;

    final ExecutorService executorService;

    @Resource
    private Cluster cluster;
    @Resource
    private Session session;
    private final StoreFeatures storeFeatures;
    private final Map<String, CQLKeyColumnValueStore> openStores;   //表以及存储记录的映射
    private final Deployment deployment;

    /**
     * Constructor for the {@link CQLStoreManager} given a JanusGraph {@link Configuration}.
     * @param configuration
     * @throws BackendException
     */
    public CQLStoreManager(final Configuration configuration) throws BackendException {
        super(configuration, DEFAULT_PORT);  //如果没有配置端口就会使用默认端口
        this.keyspace = determineKeyspaceName(configuration);
        this.batchSize = configuration.get(BATCH_STATEMENT_SIZE);
        this.atomicBatch = configuration.get(ATOMIC_BATCH_MUTATE);

        //给定了线程池
        this.executorService = new ThreadPoolExecutor(10,
                100,
                1,
                TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("CQLStoreManager[%02d]")
                        .build());

        this.cluster = initializeCluster();
        initializeJmxMetrics();
        this.session = initializeSession(this.keyspace);

        final Configuration global = buildGraphConfiguration()
                .set(READ_CONSISTENCY, CONSISTENCY_QUORUM)
                .set(WRITE_CONSISTENCY, CONSISTENCY_QUORUM)
                .set(METRICS_PREFIX, METRICS_SYSTEM_PREFIX_DEFAULT);

        final Configuration local = buildGraphConfiguration()
                .set(READ_CONSISTENCY, CONSISTENCY_LOCAL_QUORUM)
                .set(WRITE_CONSISTENCY, CONSISTENCY_LOCAL_QUORUM)
                .set(METRICS_PREFIX, METRICS_SYSTEM_PREFIX_DEFAULT);

        final Boolean onlyUseLocalConsistency = configuration.get(ONLY_USE_LOCAL_CONSISTENCY_FOR_SYSTEM_OPERATIONS);

        final Boolean useExternalLocking = configuration.get(USE_EXTERNAL_LOCKING);

        final StandardStoreFeatures.Builder fb = new StandardStoreFeatures.Builder();   //存储引擎的特点

        fb.batchMutation(true).distributed(true);
        fb.timestamps(true).cellTTL(true);
        fb.keyConsistent((onlyUseLocalConsistency ? local : global), local);
        fb.locking(useExternalLocking);
        fb.optimisticLocking(true);
        fb.multiQuery(false);

        final String partitioner = this.cluster.getMetadata().getPartitioner();
        switch (partitioner.substring(partitioner.lastIndexOf('.') + 1)) {
            case "RandomPartitioner":
            case "Murmur3Partitioner": {
                fb.keyOrdered(false).orderedScan(false).unorderedScan(true);
                deployment = Deployment.REMOTE;
                break;
            }
            case "ByteOrderedPartitioner": {
                fb.keyOrdered(true).orderedScan(true).unorderedScan(false);
                deployment = (hostnames.length == 1)// mark deployment as local only in case we have byte ordered partitioner and local
                                                    // connection
                        ? (NetworkUtil.isLocalConnection(hostnames[0])) ? Deployment.LOCAL : Deployment.REMOTE
                        : Deployment.REMOTE;
                break;
            }
            default: {
                throw new IllegalArgumentException("Unrecognized partitioner: " + partitioner);
            }
        }
        this.storeFeatures = fb.build();
        this.openStores = new ConcurrentHashMap<>();
    }

    private void initializeJmxMetrics() {
        final Configuration configuration = getStorageConfig();
        if (configuration.get(METRICS_JMX_ENABLED) && configuration.get(BASIC_METRICS)) {
            MetricManager.INSTANCE.getRegistry().registerAll(cluster.getMetrics().getRegistry());
        }
    }

    Cluster initializeCluster() throws PermanentBackendException {
        final Configuration configuration = getStorageConfig();

        final List<InetSocketAddress> contactPoints;
        try {
            contactPoints = Array.of(this.hostnames)
                    .map(hostName -> hostName.split(":"))
                    .map(array -> Tuple.of(array[0], array.length == 2 ? Integer.parseInt(array[1]) : this.port))
                    .map(tuple -> new InetSocketAddress(tuple._1, tuple._2))
                    .toJavaList();
        } catch (SecurityException | ArrayIndexOutOfBoundsException | NumberFormatException e) {
            throw new PermanentBackendException("Error initialising cluster contact points", e);
        }

        final Builder builder = Cluster.builder()
            .withoutJMXReporting()
            .addContactPointsWithPorts(contactPoints)
            .withClusterName(configuration.get(CLUSTER_NAME));

        if (configuration.get(PROTOCOL_VERSION) != 0) {
            builder.withProtocolVersion(ProtocolVersion.fromInt(configuration.get(PROTOCOL_VERSION)));
        }

        if (configuration.has(AUTH_USERNAME) && configuration.has(AUTH_PASSWORD)) {
            builder.withCredentials(configuration.get(AUTH_USERNAME), configuration.get(AUTH_PASSWORD));
        }

        if (configuration.has(LOCAL_DATACENTER)) {
            builder.withLoadBalancingPolicy(new TokenAwarePolicy(DCAwareRoundRobinPolicy.builder()
                    .withLocalDc(configuration.get(LOCAL_DATACENTER))
                    .build()));
        }

        if (configuration.get(SSL_ENABLED)) {
            try {
                KeyManager[] keyManagers = null;
                if(configuration.get(SSL_CLIENT_AUTHENTICATION_ENABLED)) {
                    try (final FileInputStream keyStoreStream = new FileInputStream(configuration.get(SSL_KEYSTORE_LOCATION))) {
                        final KeyStore keystore = KeyStore.getInstance("jks");
                        keystore.load(keyStoreStream, configuration.get(SSL_KEYSTORE_STORE_PASSWORD).toCharArray());
                        final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                        keyManagerFactory.init(keystore, configuration.get(SSL_KEYSTORE_KEY_PASSWORD).toCharArray());
                        keyManagers = keyManagerFactory.getKeyManagers();
                    }
                }
                final TrustManager[] trustManagers;
                try (final FileInputStream keyStoreStream = new FileInputStream(configuration.get(SSL_TRUSTSTORE_LOCATION))) {
                    final KeyStore keystore = KeyStore.getInstance("jks");
                    keystore.load(keyStoreStream, configuration.get(SSL_TRUSTSTORE_PASSWORD).toCharArray());
                    final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trustManagerFactory.init(keystore);
                    trustManagers = trustManagerFactory.getTrustManagers();
                }

                final SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keyManagers, trustManagers, null);

                final JdkSSLOptions sslOptions = JdkSSLOptions.builder()
                        .withSSLContext(sslContext)
                        .build();
                builder.withSSL(sslOptions);

            } catch (UnrecoverableKeyException | NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException | KeyManagementException e) {
                throw new PermanentBackendException("Error initialising SSL connection properties", e);
            }
        }

        if (!configuration.get(BASIC_METRICS)) {
            builder.withoutMetrics();
        }

        // Build the PoolingOptions based on the configurations
        PoolingOptions poolingOptions = new PoolingOptions();
        poolingOptions
            .setMaxRequestsPerConnection(
                    HostDistance.LOCAL,
                    configuration.get(LOCAL_MAX_REQUESTS_PER_CONNECTION))
            .setMaxRequestsPerConnection(
                    HostDistance.REMOTE,
                    configuration.get(REMOTE_MAX_REQUESTS_PER_CONNECTION));
        poolingOptions
            .setConnectionsPerHost(
                    HostDistance.LOCAL,
                    configuration.get(LOCAL_CORE_CONNECTIONS_PER_HOST),
                    configuration.get(LOCAL_MAX_CONNECTIONS_PER_HOST))
            .setConnectionsPerHost(
                    HostDistance.REMOTE,
                    configuration.get(REMOTE_CORE_CONNECTIONS_PER_HOST),
                    configuration.get(REMOTE_MAX_CONNECTIONS_PER_HOST));
        return builder.withPoolingOptions(poolingOptions).build();
    }

    //cql-driver的初始化session
    Session initializeSession(final String keyspaceName) {
        final Session s = this.cluster.connect();

        // if the keyspace already exists, just return the session【判断相关db是否存在】
        if (this.cluster.getMetadata().getKeyspace(keyspaceName) != null) {
            return s;
        }

        final Configuration configuration = getStorageConfig();
        // Setting replication strategy based on value reading from the configuration: either "SimpleStrategy" or "NetworkTopologyStrategy"【数据复制使用的方式】
        final Map<String, Object> replication = Match(configuration.get(REPLICATION_STRATEGY)).of(//复制策略
            Case($("SimpleStrategy"), strategy -> HashMap.<String, Object> of("class", strategy, "replication_factor", configuration.get(REPLICATION_FACTOR))),  //确定复制因子
            Case($("NetworkTopologyStrategy"),  //使用网络拓扑策略
                strategy -> HashMap.<String, Object> of("class", strategy)
                    .merge(Array.of(configuration.get(REPLICATION_OPTIONS))
                        .grouped(2)
                        .toMap(array -> Tuple.of(array.get(0), Integer.parseInt(array.get(1)))))))
            .toJavaMap();

        //执行创建db
        s.execute(createKeyspace(keyspaceName)
                .ifNotExists()
                .with()
                .replication(replication));
        return s;
    }

    ExecutorService getExecutorService() {
        return this.executorService;
    }

    Session getSession() {
        return this.session;
    }

    String getKeyspaceName() {
        return this.keyspace;
    }

    //获取压缩方式
    Map<String, String> getCompressionOptions(final String name) throws BackendException {
        final KeyspaceMetadata keyspaceMetadata = Option.of(this.cluster.getMetadata().getKeyspace(this.keyspace))
                .getOrElseThrow(() -> new PermanentBackendException(String.format("Unknown keyspace '%s'", this.keyspace)));
        return Option.of(keyspaceMetadata.getTable(name))
                .map(tableMetadata -> tableMetadata.getOptions().getCompression())
                .getOrElseThrow(() -> new PermanentBackendException(String.format("Unknown table '%s'", name)));
    }

    //获取元数据
    TableMetadata getTableMetadata(final String name) throws BackendException {
        final KeyspaceMetadata keyspaceMetadata = Option.of(this.cluster.getMetadata().getKeyspace(this.keyspace))
                .getOrElseThrow(() -> new PermanentBackendException(String.format("Unknown keyspace '%s'", this.keyspace)));
        return Option.of(keyspaceMetadata.getTable(name))
                .getOrElseThrow(() -> new PermanentBackendException(String.format("Unknown table '%s'", name)));
    }

    @Override
    public void close() throws BackendException {
        try {
            this.session.close();
        } finally {
            try {
                this.cluster.close();
            } finally {
                this.executorService.shutdownNow();
            }
        }
    }

    @Override
    public String getName() {
        return String.format("%s.%s", getClass().getSimpleName(), this.keyspace);
    }

    @Override
    public Deployment getDeployment() {
        return this.deployment;
    }

    @Override
    public StoreFeatures getFeatures() {
        return this.storeFeatures;
    }

    //构建数据存储
    @Override
    public KeyColumnValueStore openDatabase(final String name, final Container metaData) throws BackendException {
        Supplier<Boolean> initializeTable = () -> Optional.ofNullable(this.cluster.getMetadata().getKeyspace(this.keyspace)).map(k -> k.getTable(name) == null).orElse(true);
        return this.openStores.computeIfAbsent(name, n -> new CQLKeyColumnValueStore(this, n, getStorageConfig(), () -> this.openStores.remove(n), initializeTable));
    }

    @Override
    public StoreTransaction beginTransaction(final BaseTransactionConfig config) throws BackendException {
        return new CQLTransaction(config);
    }

    @Override
    public void clearStorage() throws BackendException {
        if (this.storageConfig.get(DROP_ON_CLEAR)) {
            this.session.execute(dropKeyspace(this.keyspace));  //删除数据库
        } else if (this.exists()) {
            final Future<Seq<ResultSet>> result = Future.sequence(
                Iterator.ofAll(this.cluster.getMetadata().getKeyspace(this.keyspace).getTables())
                    .map(table -> Future.fromJavaFuture(this.session.executeAsync(truncate(this.keyspace, table.getName())))));
            result.await();
        } else {
            LOGGER.info("Keyspace {} does not exist in the cluster", this.keyspace);
        }
    }

    @Override
    public boolean exists() throws BackendException {
        return cluster.getMetadata().getKeyspace(this.keyspace) != null;
    }

    @Override
    public List<KeyRange> getLocalKeyPartition() throws BackendException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void mutateMany(final Map<String, Map<StaticBuffer, KCVMutation>> mutations, final StoreTransaction txh) throws BackendException {
        if (this.atomicBatch) {
            mutateManyLogged(mutations, txh);
        } else {
            mutateManyUnlogged(mutations, txh);  //无日志batch处理
        }
    }

    // Use a single logged batch
    private void mutateManyLogged(final Map<String, Map<StaticBuffer, KCVMutation>> mutations, final StoreTransaction txh) throws BackendException {
        final MaskedTimestamp commitTime = new MaskedTimestamp(txh);

        final BatchStatement batchStatement = new BatchStatement(Type.LOGGED);  //批量处理mutation【在一个批处理中相当于一个事务操作】
        batchStatement.setConsistencyLevel(getTransaction(txh).getWriteConsistencyLevel());  //确定一致性Level

        batchStatement.addAll(Iterator.ofAll(mutations.entrySet()).flatMap(tableNameAndMutations -> {
            final String tableName = tableNameAndMutations.getKey();  //获取表名称
            final Map<StaticBuffer, KCVMutation> tableMutations = tableNameAndMutations.getValue();  //数据

            final CQLKeyColumnValueStore columnValueStore = Option.of(this.openStores.get(tableName))
                    .getOrElseThrow(() -> new IllegalStateException("Store cannot be found: " + tableName));
            return Iterator.ofAll(tableMutations.entrySet()).flatMap(keyAndMutations -> {
                final StaticBuffer key = keyAndMutations.getKey();  //key数据
                final KCVMutation keyMutations = keyAndMutations.getValue();  //KCV结构

                //处理删除数据操作
                final Iterator<Statement> deletions = Iterator.of(commitTime.getDeletionTime(this.times))
                        .flatMap(deleteTime -> Iterator.ofAll(keyMutations.getDeletions()).map(deletion -> columnValueStore.deleteColumn(key, deletion, deleteTime)));

                //处理添加数据操作
                final Iterator<Statement> additions = Iterator.of(commitTime.getAdditionTime(this.times))
                        .flatMap(addTime -> Iterator.ofAll(keyMutations.getAdditions()).map(addition -> columnValueStore.insertColumn(key, addition, addTime)));

                return Iterator.concat(deletions, additions);
            });
        }));

        //异步执行生成的cal语句
        final Future<ResultSet> result = Future.fromJavaFuture(this.executorService, this.session.executeAsync(batchStatement));

        result.await(); //等待结果执行完成
        if (result.isFailure()) {
            throw EXCEPTION_MAPPER.apply(result.getCause().get());
        }
        sleepAfterWrite(txh, commitTime);  //事务方面保证，通过时间？
    }

    // Create an async un-logged batch per partition key
    private void mutateManyUnlogged(final Map<String, Map<StaticBuffer, KCVMutation>> mutations, final StoreTransaction txh) throws BackendException {
        final MaskedTimestamp commitTime = new MaskedTimestamp(txh);

        final Future<Seq<ResultSet>> result = Future.sequence(this.executorService, Iterator.ofAll(mutations.entrySet()).flatMap(tableNameAndMutations -> {
            final String tableName = tableNameAndMutations.getKey();  //表名
            final Map<StaticBuffer, KCVMutation> tableMutations = tableNameAndMutations.getValue();  //数据

            final CQLKeyColumnValueStore columnValueStore = Option.of(this.openStores.get(tableName))
                    .getOrElseThrow(() -> new IllegalStateException("Store cannot be found: " + tableName));
            return Iterator.ofAll(tableMutations.entrySet()).flatMap(keyAndMutations -> {
                final StaticBuffer key = keyAndMutations.getKey();
                final KCVMutation keyMutations = keyAndMutations.getValue();

                final Iterator<Statement> deletions = Iterator.of(commitTime.getDeletionTime(this.times))
                        .flatMap(deleteTime -> Iterator.ofAll(keyMutations.getDeletions()).map(deletion -> columnValueStore.deleteColumn(key, deletion, deleteTime)));
                final Iterator<Statement> additions = Iterator.of(commitTime.getAdditionTime(this.times))
                        .flatMap(addTime -> Iterator.ofAll(keyMutations.getAdditions()).map(addition -> columnValueStore.insertColumn(key, addition, addTime)));

                return Iterator.concat(deletions, additions)
                        .grouped(this.batchSize)  //按照batchSize来完成分组
                        .map(group -> Future.fromJavaFuture(this.executorService,  //按照每个分组执行
                                this.session.executeAsync(
                                        new BatchStatement(Type.UNLOGGED)  //每个分组都定义无日志
                                                .addAll(group)
                                                .setConsistencyLevel(getTransaction(txh).getWriteConsistencyLevel())))); //给定一个一致性写入要求
            });
        }));

        result.await();
        if (result.isFailure()) {
            throw EXCEPTION_MAPPER.apply(result.getCause().get());
        }
        sleepAfterWrite(txh, commitTime);
    }

    private String determineKeyspaceName(Configuration config) {
        if ((!config.has(KEYSPACE) && (config.has(GRAPH_NAME)))) return config.get(GRAPH_NAME);
        return config.get(KEYSPACE);
    }

    @Override
    public Object getHadoopManager() {
        return new CqlHadoopStoreManager(this.cluster);
    }
}
