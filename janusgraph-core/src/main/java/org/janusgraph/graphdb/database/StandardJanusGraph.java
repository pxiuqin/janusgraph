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

package org.janusgraph.graphdb.database;

import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.REGISTRATION_TIME;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.REPLACE_INSTANCE_IF_EXISTS;

import com.carrotsearch.hppc.LongArrayList;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.janusgraph.core.*;
import org.janusgraph.core.schema.ConsistencyModifier;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.SchemaStatus;
import org.janusgraph.diskstorage.*;
import org.janusgraph.diskstorage.configuration.BasicConfiguration;
import org.janusgraph.diskstorage.configuration.Configuration;
import org.janusgraph.diskstorage.configuration.ModifiableConfiguration;
import org.janusgraph.diskstorage.indexing.IndexEntry;
import org.janusgraph.diskstorage.indexing.IndexTransaction;
import org.janusgraph.diskstorage.keycolumnvalue.*;
import org.janusgraph.diskstorage.keycolumnvalue.cache.KCVSCache;
import org.janusgraph.diskstorage.log.Log;
import org.janusgraph.diskstorage.log.Message;
import org.janusgraph.diskstorage.log.ReadMarker;
import org.janusgraph.diskstorage.log.kcvs.KCVSLog;
import org.janusgraph.diskstorage.util.RecordIterator;
import org.janusgraph.diskstorage.util.StaticArrayEntry;
import org.janusgraph.diskstorage.util.time.TimestampProvider;
import org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration;
import org.janusgraph.graphdb.database.cache.SchemaCache;
import org.janusgraph.graphdb.database.idassigner.VertexIDAssigner;
import org.janusgraph.graphdb.database.idhandling.IDHandler;
import org.janusgraph.graphdb.database.log.LogTxStatus;
import org.janusgraph.graphdb.database.log.TransactionLogHeader;
import org.janusgraph.graphdb.database.management.ManagementLogger;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.janusgraph.graphdb.database.serialize.Serializer;
import org.janusgraph.graphdb.idmanagement.IDManager;
import org.janusgraph.graphdb.internal.InternalRelation;
import org.janusgraph.graphdb.internal.InternalRelationType;
import org.janusgraph.graphdb.internal.InternalVertex;
import org.janusgraph.graphdb.internal.InternalVertexLabel;
import org.janusgraph.graphdb.query.QueryUtil;
import org.janusgraph.graphdb.query.index.ApproximateIndexSelectionStrategy;
import org.janusgraph.graphdb.query.index.BruteForceIndexSelectionStrategy;
import org.janusgraph.graphdb.query.index.IndexSelectionStrategy;
import org.janusgraph.graphdb.query.index.ThresholdBasedIndexSelectionStrategy;
import org.janusgraph.graphdb.relations.EdgeDirection;
import org.janusgraph.graphdb.tinkerpop.JanusGraphBlueprintsGraph;
import org.janusgraph.graphdb.tinkerpop.JanusGraphFeatures;
import org.janusgraph.graphdb.tinkerpop.optimize.AdjacentVertexFilterOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.AdjacentVertexIsOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.AdjacentVertexHasIdOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.JanusGraphIoRegistrationStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.JanusGraphLocalQueryOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.JanusGraphStepStrategy;
import org.janusgraph.graphdb.transaction.StandardJanusGraphTx;
import org.janusgraph.graphdb.transaction.StandardTransactionBuilder;
import org.janusgraph.graphdb.transaction.TransactionConfiguration;
import org.janusgraph.graphdb.types.CompositeIndexType;
import org.janusgraph.graphdb.types.MixedIndexType;
import org.janusgraph.graphdb.types.system.BaseKey;
import org.janusgraph.graphdb.types.system.BaseRelationType;
import org.janusgraph.graphdb.types.vertices.JanusGraphSchemaVertex;
import org.janusgraph.graphdb.util.ExceptionFactory;
import org.janusgraph.util.system.IOUtils;
import org.janusgraph.util.system.TXUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StandardJanusGraph extends JanusGraphBlueprintsGraph {

    private static final Logger log =
            LoggerFactory.getLogger(StandardJanusGraph.class);


    static {
        TraversalStrategies graphStrategies =
            TraversalStrategies.GlobalCache.getStrategies(Graph.class)
                .clone()
                .addStrategies(AdjacentVertexFilterOptimizerStrategy.instance(),
                               AdjacentVertexHasIdOptimizerStrategy.instance(),
                               AdjacentVertexIsOptimizerStrategy.instance(),
                               JanusGraphLocalQueryOptimizerStrategy.instance(),
                               JanusGraphStepStrategy.instance(),
                               JanusGraphIoRegistrationStrategy.instance());

        //Register with cache
        TraversalStrategies.GlobalCache.registerStrategies(StandardJanusGraph.class, graphStrategies);
        TraversalStrategies.GlobalCache.registerStrategies(StandardJanusGraphTx.class, graphStrategies);
    }

    private final GraphDatabaseConfiguration config;
    private final Backend backend;
    private final IDManager idManager;
    private final VertexIDAssigner idAssigner;
    private final TimestampProvider times;

    //Serializers
    protected final IndexSerializer indexSerializer;
    protected final EdgeSerializer edgeSerializer;
    protected final Serializer serializer;

    //Caches
    public final SliceQuery vertexExistenceQuery;  //节点
    private final RelationQueryCache queryCache;
    private final SchemaCache schemaCache;

    //Log
    private final ManagementLogger managementLogger;

    //Shutdown hook
    private volatile ShutdownThread shutdownHook;

    //Index selection
    private final IndexSelectionStrategy indexSelector;

    private volatile boolean isOpen;
    private final AtomicLong txCounter;

    private final Set<StandardJanusGraphTx> openTransactions;

    private final String name;

    //构建标准图
    public StandardJanusGraph(GraphDatabaseConfiguration configuration) {

        this.config = configuration;
        this.backend = configuration.getBackend();

        this.name = configuration.getGraphName();

        this.idAssigner = config.getIDAssigner(backend);
        this.idManager = idAssigner.getIDManager();

        this.serializer = config.getSerializer();  //配置给定了StandardSerializer
        StoreFeatures storeFeatures = backend.getStoreFeatures();  //存储特性
        this.indexSerializer = new IndexSerializer(configuration.getConfiguration(), this.serializer,
                this.backend.getIndexInformation(), storeFeatures.isDistributed() && storeFeatures.isKeyOrdered());
        this.edgeSerializer = new EdgeSerializer(this.serializer);
        this.vertexExistenceQuery = edgeSerializer.getQuery(BaseKey.VertexExists, Direction.OUT, new EdgeSerializer.TypedInterval[0]).setLimit(1);
        this.queryCache = new RelationQueryCache(this.edgeSerializer);
        this.schemaCache = configuration.getTypeCache(typeCacheRetrieval);
        this.times = configuration.getTimestampProvider();
        this.indexSelector = new ThresholdBasedIndexSelectionStrategy(getConfiguration().getIndexSelectThreshold(),
            new BruteForceIndexSelectionStrategy(),
            new ApproximateIndexSelectionStrategy());

        isOpen = true;
        txCounter = new AtomicLong(0);
        openTransactions = Collections.newSetFromMap(new ConcurrentHashMap<StandardJanusGraphTx, Boolean>(100, 0.75f, 1));

        //Register instance and ensure uniqueness
        String uniqueInstanceId = configuration.getUniqueGraphId();
        ModifiableConfiguration globalConfig = getGlobalSystemConfig(backend);
        final boolean instanceExists = globalConfig.has(REGISTRATION_TIME, uniqueInstanceId);
        final boolean replaceExistingInstance = configuration.getConfiguration().get(REPLACE_INSTANCE_IF_EXISTS);
        if (instanceExists && !replaceExistingInstance) {
            throw new JanusGraphException(String.format("A JanusGraph graph with the same instance id [%s] is already open. Might required forced shutdown.", uniqueInstanceId));
        } else if (instanceExists && replaceExistingInstance) {
            log.debug(String.format("Instance [%s] already exists. Opening the graph per " + REPLACE_INSTANCE_IF_EXISTS.getName() + " configuration.", uniqueInstanceId));
        }
        globalConfig.set(REGISTRATION_TIME, times.getTime(), uniqueInstanceId);

        Log managementLog = backend.getSystemMgmtLog();
        managementLogger = new ManagementLogger(this, managementLog, schemaCache, this.times);
        managementLog.registerReader(ReadMarker.fromNow(), managementLogger);

        shutdownHook = new ShutdownThread(this);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.debug("Installed shutdown hook {}", shutdownHook, new Throwable("Hook creation trace"));
    }

    public String getGraphName() {
        return this.name;
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public boolean isClosed() {
        return !isOpen();
    }

    @Override
    public synchronized void close() throws JanusGraphException {
        try {
            closeInternal();
        } finally {
            removeHook();
        }
    }

    private synchronized void closeInternal() {

        if (!isOpen) return;

        Map<JanusGraphTransaction, RuntimeException> txCloseExceptions = new HashMap<>();

        try {
            //Unregister instance
            String uniqueId = null;
            try {
                uniqueId = config.getUniqueGraphId();
                ModifiableConfiguration globalConfig = getGlobalSystemConfig(backend);
                globalConfig.remove(REGISTRATION_TIME, uniqueId);
            } catch (Exception e) {
                log.warn("Unable to remove graph instance uniqueid {}", uniqueId, e);
            }

            /* Assuming a couple of properties about openTransactions:
             * 1. no concurrent modifications during graph shutdown
             * 2. all contained txs are open
             */
            for (StandardJanusGraphTx otx : openTransactions) {
                try {
                    otx.rollback();
                    otx.close();
                } catch (RuntimeException e) {
                    // Catch and store these exceptions, but proceed wit the loop
                    // Any remaining txs on the iterator should get a chance to close before we throw up
                    log.warn("Unable to close transaction {}", otx, e);
                    txCloseExceptions.put(otx, e);
                }
            }

            super.close();

            IOUtils.closeQuietly(idAssigner);
            IOUtils.closeQuietly(backend);
            IOUtils.closeQuietly(queryCache);
            IOUtils.closeQuietly(serializer);
        } finally {
            isOpen = false;
        }

        // Throw an exception if at least one transaction failed to close
        if (1 == txCloseExceptions.size()) {
            // TP3's test suite requires that this be of type ISE
            throw new IllegalStateException("Unable to close transaction",
                    Iterables.getOnlyElement(txCloseExceptions.values()));
        } else if (1 < txCloseExceptions.size()) {
            throw new IllegalStateException(String.format(
                    "Unable to close %s transactions (see warnings in log output for details)",
                    txCloseExceptions.size()));
        }
    }

    private synchronized void removeHook() {
        if (null == shutdownHook)
                return;

        ShutdownThread tmp = shutdownHook;
        shutdownHook = null;
        // Remove shutdown hook to avoid reference retention
        try {
            Runtime.getRuntime().removeShutdownHook(tmp);
            log.debug("Removed shutdown hook {}", tmp);
        } catch (IllegalStateException e) {
            log.warn("Failed to remove shutdown hook", e);
        }
    }


    // ################### Simple Getters #########################

    @Override
    public Features features() {
        return JanusGraphFeatures.getFeatures(this, backend.getStoreFeatures());
    }


    public IndexSerializer getIndexSerializer() {
        return indexSerializer;
    }

    public IndexSelectionStrategy getIndexSelector() {
        return indexSelector;
    }

    public Backend getBackend() {
        return backend;
    }

    public IDManager getIDManager() {
        return idManager;
    }

    public EdgeSerializer getEdgeSerializer() {
        return edgeSerializer;
    }

    public Serializer getDataSerializer() {
        return serializer;
    }

    //TODO: premature optimization, re-evaluate later
//    public RelationQueryCache getQueryCache() {
//        return queryCache;
//    }

    public SchemaCache getSchemaCache() {
        return schemaCache;
    }

    public GraphDatabaseConfiguration getConfiguration() {
        return config;
    }

    @Override
    public JanusGraphManagement openManagement() {
        return new ManagementSystem(this,backend.getGlobalSystemConfig(),backend.getSystemMgmtLog(), managementLogger, schemaCache);
    }

    public Set<? extends JanusGraphTransaction> getOpenTransactions() {
        return Sets.newHashSet(openTransactions);
    }

    // ################### TRANSACTIONS #########################

    @Override
    public JanusGraphTransaction newTransaction() {
        return buildTransaction().start();
    }

    @Override
    public StandardTransactionBuilder buildTransaction() {
        return new StandardTransactionBuilder(getConfiguration(), this);
    }

    @Override
    public JanusGraphTransaction newThreadBoundTransaction() {
        return buildTransaction().threadBound().start();
    }

    public StandardJanusGraphTx newTransaction(final TransactionConfiguration configuration) {
        if (!isOpen) ExceptionFactory.graphShutdown();
        try {
            StandardJanusGraphTx tx = new StandardJanusGraphTx(this, configuration);
            tx.setBackendTransaction(openBackendTransaction(tx));
            openTransactions.add(tx);
            return tx;
        } catch (BackendException e) {
            throw new JanusGraphException("Could not start new transaction", e);
        }
    }

    private BackendTransaction openBackendTransaction(StandardJanusGraphTx tx) throws BackendException {
        IndexSerializer.IndexInfoRetriever retriever = indexSerializer.getIndexInfoRetriever(tx);
        return backend.beginTransaction(tx.getConfiguration(), retriever);
    }

    public void closeTransaction(StandardJanusGraphTx tx) {
        openTransactions.remove(tx);
    }

    // ################### READ #########################

    private final SchemaCache.StoreRetrieval typeCacheRetrieval = new SchemaCache.StoreRetrieval() {

        @Override
        public Long retrieveSchemaByName(String typeName) {
            // Get a consistent tx
            Configuration customTxOptions = backend.getStoreFeatures().getKeyConsistentTxConfig();
            StandardJanusGraphTx consistentTx = null;
            try {
                consistentTx = StandardJanusGraph.this.newTransaction(new StandardTransactionBuilder(getConfiguration(),
                        StandardJanusGraph.this, customTxOptions).groupName(GraphDatabaseConfiguration.METRICS_SCHEMA_PREFIX_DEFAULT));
                consistentTx.getTxHandle().disableCache();
                JanusGraphVertex v = Iterables.getOnlyElement(QueryUtil.getVertices(consistentTx, BaseKey.SchemaName, typeName), null);
                return v!=null?v.longId():null;
            } finally {
                TXUtils.rollbackQuietly(consistentTx);
            }
        }

        @Override
        public EntryList retrieveSchemaRelations(final long schemaId, final BaseRelationType type, final Direction dir) {
            SliceQuery query = queryCache.getQuery(type,dir);
            Configuration customTxOptions = backend.getStoreFeatures().getKeyConsistentTxConfig();
            StandardJanusGraphTx consistentTx = null;
            try {
                consistentTx = StandardJanusGraph.this.newTransaction(new StandardTransactionBuilder(getConfiguration(),
                        StandardJanusGraph.this, customTxOptions).groupName(GraphDatabaseConfiguration.METRICS_SCHEMA_PREFIX_DEFAULT));
                consistentTx.getTxHandle().disableCache();
                return edgeQuery(schemaId, query, consistentTx.getTxHandle());
            } finally {
                TXUtils.rollbackQuietly(consistentTx);
            }
        }

    };

    public RecordIterator<Long> getVertexIDs(final BackendTransaction tx) {
        Preconditions.checkArgument(backend.getStoreFeatures().hasOrderedScan() ||
                backend.getStoreFeatures().hasUnorderedScan(),
                "The configured storage backend does not support global graph operations - use Faunus instead");

        final KeyIterator keyIterator;
        if (backend.getStoreFeatures().hasUnorderedScan()) {
            keyIterator = tx.edgeStoreKeys(vertexExistenceQuery);
        } else {
            keyIterator = tx.edgeStoreKeys(new KeyRangeQuery(IDHandler.MIN_KEY, IDHandler.MAX_KEY, vertexExistenceQuery));
        }

        return new RecordIterator<Long>() {

            @Override
            public boolean hasNext() {
                return keyIterator.hasNext();
            }

            @Override
            public Long next() {
                return idManager.getKeyID(keyIterator.next());
            }

            @Override
            public void close() throws IOException {
                keyIterator.close();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Removal not supported");
            }
        };
    }

    public EntryList edgeQuery(long vid, SliceQuery query, BackendTransaction tx) {
        Preconditions.checkArgument(vid > 0);
        return tx.edgeStoreQuery(new KeySliceQuery(idManager.getKey(vid), query));
    }

    //
    public List<EntryList> edgeMultiQuery(LongArrayList vertexIdsAsLongs, SliceQuery query, BackendTransaction tx) {
        Preconditions.checkArgument(vertexIdsAsLongs != null && !vertexIdsAsLongs.isEmpty());
        final List<StaticBuffer> vertexIds = new ArrayList<>(vertexIdsAsLongs.size());
        for (int i = 0; i < vertexIdsAsLongs.size(); i++) {
            Preconditions.checkArgument(vertexIdsAsLongs.get(i) > 0);
            vertexIds.add(idManager.getKey(vertexIdsAsLongs.get(i)));
        }
        final Map<StaticBuffer,EntryList> result = tx.edgeStoreMultiQuery(vertexIds, query);
        final List<EntryList> resultList = new ArrayList<>(result.size());
        for (StaticBuffer v : vertexIds) resultList.add(result.get(v));
        return resultList;
    }

    private ModifiableConfiguration getGlobalSystemConfig(Backend backend) {

        return new ModifiableConfiguration(GraphDatabaseConfiguration.ROOT_NS,
            backend.getGlobalSystemConfig(), BasicConfiguration.Restriction.GLOBAL);
    }

    // ################### WRITE #########################

    public void assignID(InternalRelation relation) {
        idAssigner.assignID(relation);
    }

    public void assignID(InternalVertex vertex, VertexLabel label) {
        idAssigner.assignID(vertex,label);
    }

    public static boolean acquireLock(InternalRelation relation, int pos, boolean acquireLocksConfig) {
        InternalRelationType type = (InternalRelationType)relation.getType();
        return acquireLocksConfig && type.getConsistencyModifier()== ConsistencyModifier.LOCK &&
                ( type.multiplicity().isUnique(EdgeDirection.fromPosition(pos))
                        || pos==0 && type.multiplicity()== Multiplicity.SIMPLE);
    }

    public static boolean acquireLock(CompositeIndexType index, boolean acquireLocksConfig) {
        return acquireLocksConfig && index.getConsistencyModifier()==ConsistencyModifier.LOCK
                && index.getCardinality()!= Cardinality.LIST;
    }

    /**
     * The TTL of a relation (edge or property) is the minimum of:
     * 1) The TTL configured of the relation type (if exists)
     * 2) The TTL configured for the label any of the relation end point vertices (if exists)
     *
     * @param rel relation to determine the TTL for
     * @return
     */
    public static int getTTL(InternalRelation rel) {
        assert rel.isNew();
        InternalRelationType baseType = (InternalRelationType) rel.getType();
        assert baseType.getBaseType()==null;
        int ttl = 0;
        Integer ettl = baseType.getTTL();
        if (ettl>0) ttl = ettl;
        for (int i=0;i<rel.getArity();i++) {
            int vttl = getTTL(rel.getVertex(i));
            if (vttl>0 && (vttl<ttl || ttl<=0)) ttl = vttl;
        }
        return ttl;
    }

    public static int getTTL(InternalVertex v) {
        assert v.hasId();
        if (IDManager.VertexIDType.UnmodifiableVertex.is(v.longId())) {
            assert v.isNew() : "Should not be able to add relations to existing static vertices: " + v;
            return ((InternalVertexLabel)v.vertexLabel()).getTTL();
        } else return 0;
    }

    private static class ModificationSummary {

        final boolean hasModifications;
        final boolean has2iModifications;

        private ModificationSummary(boolean hasModifications, boolean has2iModifications) {
            this.hasModifications = hasModifications;
            this.has2iModifications = has2iModifications;
        }
    }

    //准备提交数据，基于添加关系和删除关系
    public ModificationSummary prepareCommit(final Collection<InternalRelation> addedRelations,
                                     final Collection<InternalRelation> deletedRelations,
                                     final Predicate<InternalRelation> filter,
                                     final BackendTransaction mutator, final StandardJanusGraphTx tx,
                                     final boolean acquireLocks) throws BackendException {


        ListMultimap<Long, InternalRelation> mutations = ArrayListMultimap.create(); //保存所有变化的Relation
        ListMultimap<InternalVertex, InternalRelation> mutatedProperties = ArrayListMultimap.create();  //变化的属性存储
        List<IndexSerializer.IndexUpdate> indexUpdates = Lists.newArrayList();  //所有索引的更新
        //1) Collect deleted edges and their index updates and acquire edge locks【处理删除关系的处理】
        for (InternalRelation del : Iterables.filter(deletedRelations,filter)) {
            Preconditions.checkArgument(del.isRemoved());
            for (int pos = 0; pos < del.getLen(); pos++) {
                InternalVertex vertex = del.getVertex(pos);  //获取节点ID
                if (pos == 0 || !del.isLoop()) {
                    if (del.isProperty()) mutatedProperties.put(vertex,del);
                    mutations.put(vertex.longId(), del);  //记录下相关情况
                }
                if (acquireLock(del,pos,acquireLocks)) {
                    Entry entry = edgeSerializer.writeRelation(del, pos, tx);
                    mutator.acquireEdgeLock(idManager.getKey(vertex.longId()), entry);
                }
            }
            indexUpdates.addAll(indexSerializer.getIndexUpdates(del));
        }

        //2) Collect added edges and their index updates and acquire edge locks【处理添加关系的处理】
        for (InternalRelation add : Iterables.filter(addedRelations,filter)) {
            Preconditions.checkArgument(add.isNew());
            for (int pos = 0; pos < add.getLen(); pos++) {
                InternalVertex vertex = add.getVertex(pos);  //vertex拿到的就是这个InternalRelation中涉及的节点
                //如果pos==0 且没有循环，才将这个节点放到变化集合和属性变化集合中
                if (pos == 0 || !add.isLoop()) {
                    //isProperty为true,加入到属性集合中
                    if (add.isProperty()) mutatedProperties.put(vertex,add);
                    mutations.put(vertex.longId(), add);
                }
                // vertex.isNew==true 节点是新增的
                if (!vertex.isNew() && acquireLock(add,pos,acquireLocks)) {
                    Entry entry = edgeSerializer.writeRelation(add, pos, tx);
                    mutator.acquireEdgeLock(idManager.getKey(vertex.longId()), entry.getColumn());
                }
            }
            //没有涉及索引
            indexUpdates.addAll(indexSerializer.getIndexUpdates(add));
        }

        //3) Collect all index update for vertices【处理索引集合】
        // 看完addRelations中的InternalRelation中的索引后再看下属性更新集合中的key也就是InternalVertex是否涉及索引
        for (InternalVertex v : mutatedProperties.keySet()) {
            indexUpdates.addAll(indexSerializer.getIndexUpdates(v,mutatedProperties.get(v)));
        }
        //4) Acquire index locks (deletions first)
        for (IndexSerializer.IndexUpdate update : indexUpdates) {
            if (!update.isCompositeIndex() || !update.isDeletion()) continue;
            CompositeIndexType iIndex = (CompositeIndexType) update.getIndex();
            if (acquireLock(iIndex,acquireLocks)) {
                mutator.acquireIndexLock((StaticBuffer)update.getKey(), (Entry)update.getEntry());
            }
        }
        for (IndexSerializer.IndexUpdate update : indexUpdates) {
            if (!update.isCompositeIndex() || !update.isAddition()) continue;
            CompositeIndexType iIndex = (CompositeIndexType) update.getIndex();
            if (acquireLock(iIndex,acquireLocks)) {
                mutator.acquireIndexLock((StaticBuffer)update.getKey(), ((Entry)update.getEntry()).getColumn());
            }
        }

        //5) Add relation mutations
        for (Long vertexId : mutations.keySet()) {
            Preconditions.checkArgument(vertexId > 0, "Vertex has no id: %s", vertexId);
            // 获取vertex对应的InternalRelation此处仍然是那两个
            final List<InternalRelation> edges = mutations.get(vertexId);  //关系构建
            // 增加的Entry列表和删除的Entry列表，这里Entry应为cassandra的存储模型，至于ArrayList的大小，应该是数学推导出来的吧，先往下看
            final List<Entry> additions = new ArrayList<>(edges.size());  //关系的添加情况
            final List<Entry> deletions = new ArrayList<>(Math.max(10, edges.size() / 10));  //关系的删除

            //迭代处理关系
            for (final InternalRelation edge : edges) {
                // 第一个InternalRelation edge为表示节点v存在的属性模型，实例为StandardVertexProperty，取出的InternalRelationType baseType实现为BaseKey
                final InternalRelationType baseType = (InternalRelationType) edge.getType();  //分类型来弄
                assert baseType.getBaseType()==null;

                //遍历类型
                for (InternalRelationType type : baseType.getRelationIndexes()) {
                    //调用到了EmptyRelationType抽象类中实现的getRelationIndexex()方法，取到的仍然是BaseKey本身，getRelationIndexes具体获得的是什么待研究
                    if (type.getStatus()== SchemaStatus.DISABLED) continue;
                    // edge若继承自AbstractEdge arity=2 若继承自AbstractVertexPropery arity = 1, BaseKey arity = 1，此处arity表示的是这个InternalRelationType涉及几个节点，源码解析见下文
                    for (int pos = 0; pos < edge.getArity(); pos++) {  //关系的节点数
                        // type.isUnidirected在BaseKey中的实现见下文，即传入的方向如果是OUT即视为单向
                        if (!type.isUnidirected(Direction.BOTH) && !type.isUnidirected(EdgeDirection.fromPosition(pos)))
                            continue; //Directionality is not covered
                        if (edge.getVertex(pos).longId()==vertexId) {
                            // 序列化后应该就是存入cassandra中的数据，这个方法深入看下，源码解析见下文，传入的当前的InternalRelation和InternalRelationType以及对应节点的位置和StandardJanusGraphTx对象
                            StaticArrayEntry entry = edgeSerializer.writeRelation(edge, type, pos, tx);  //写入关系
                            // entry已经拿到了，buffer里面就是以上分析中写的一些序列化的数据
                            if (edge.isRemoved()) {
                                deletions.add(entry);  //删除
                            } else {
                                Preconditions.checkArgument(edge.isNew());
                                int ttl = getTTL(edge);
                                if (ttl > 0) {
                                    entry.setMetaData(EntryMetaData.TTL, ttl);
                                }
                                additions.add(entry);   //添加
                            }
                        }
                    }
                }
            }

            StaticBuffer vertexKey = idManager.getKey(vertexId);
            //序列化完成后，接下来要跟具体存储处理，比如：cassandra，实际上做的事就是把之前序列化的结果放到mutator中转换成cassandra的数据结构
            mutator.mutateEdges(vertexKey, additions, deletions);
        }

        //6) Add index updates
        boolean has2iMods = false;
        for (IndexSerializer.IndexUpdate indexUpdate : indexUpdates) {
            assert indexUpdate.isAddition() || indexUpdate.isDeletion();
            if (indexUpdate.isCompositeIndex()) {
                final IndexSerializer.IndexUpdate<StaticBuffer,Entry> update = indexUpdate;
                if (update.isAddition())
                    mutator.mutateIndex(update.getKey(), Lists.newArrayList(update.getEntry()), KCVSCache.NO_DELETIONS);
                else
                    mutator.mutateIndex(update.getKey(), KeyColumnValueStore.NO_ADDITIONS, Lists.newArrayList(update.getEntry()));
            } else {
                final IndexSerializer.IndexUpdate<String,IndexEntry> update = indexUpdate;
                has2iMods = true;
                IndexTransaction itx = mutator.getIndexTransaction(update.getIndex().getBackingIndexName());
                String indexStore = ((MixedIndexType)update.getIndex()).getStoreName();
                if (update.isAddition())
                    itx.add(indexStore, update.getKey(), update.getEntry(), update.getElement().isNew());
                else
                    itx.delete(indexStore,update.getKey(),update.getEntry().field,update.getEntry().value,update.getElement().isRemoved());
            }
        }
        return new ModificationSummary(!mutations.isEmpty(),has2iMods);
    }

    private static final Predicate<InternalRelation> SCHEMA_FILTER =
        internalRelation -> internalRelation.getType() instanceof BaseRelationType && internalRelation.getVertex(0) instanceof JanusGraphSchemaVertex;

    private static final Predicate<InternalRelation> NO_SCHEMA_FILTER = internalRelation -> !SCHEMA_FILTER.apply(internalRelation);

    private static final Predicate<InternalRelation> NO_FILTER = Predicates.alwaysTrue();

    //对添加和删除操作进行commit
    public void commit(final Collection<InternalRelation> addedRelations,
                     final Collection<InternalRelation> deletedRelations, final StandardJanusGraphTx tx) {
        if (addedRelations.isEmpty() && deletedRelations.isEmpty()) return;
        //1. Finalize transaction
        log.debug("Saving transaction. Added {}, removed {}", addedRelations.size(), deletedRelations.size());
        if (!tx.getConfiguration().hasCommitTime()) tx.getConfiguration().setCommitTime(times.getTime());  //没有提交时间的设置相关时间
        final Instant txTimestamp = tx.getConfiguration().getCommitTime();
        final long transactionId = txCounter.incrementAndGet();

        //2. Assign JanusGraphVertex IDs【根据配置是否自动分配ID】
        if (!tx.getConfiguration().hasAssignIDsImmediately())
            idAssigner.assignIDs(addedRelations);

        //3. Commit
        BackendTransaction mutator = tx.getTxHandle();
        final boolean acquireLocks = tx.getConfiguration().hasAcquireLocks();  //是否获得锁
        final boolean hasTxIsolation = backend.getStoreFeatures().hasTxIsolation();  //隔离性的体现，如果是cassandra保证最终一致性这里就是false
        final boolean logTransaction = config.hasLogTransactions() && !tx.getConfiguration().hasEnabledBatchLoading();  //日志事务，这里如果是批处理就不进行日志事务
        final KCVSLog txLog = logTransaction?backend.getSystemTxLog():null;
        final TransactionLogHeader txLogHeader = new TransactionLogHeader(transactionId,txTimestamp, times);
        ModificationSummary commitSummary;

        try {
            //3.1 Log transaction (write-ahead log) if enabled
            if (logTransaction) {
                //[FAILURE] Inability to log transaction fails the transaction by escalation since it's likely due to unavailability of primary
                //storage backend.
                Preconditions.checkNotNull(txLog, "Transaction log is null");
                txLog.add(txLogHeader.serializeModifications(serializer, LogTxStatus.PRECOMMIT, tx, addedRelations, deletedRelations),txLogHeader.getLogKey());
            }

            //3.2 Commit schema elements and their associated relations in a separate transaction if backend does not support
            //    transactional isolation
            boolean hasSchemaElements = !Iterables.isEmpty(Iterables.filter(deletedRelations,SCHEMA_FILTER))
                    || !Iterables.isEmpty(Iterables.filter(addedRelations,SCHEMA_FILTER));  //是否修改了schema

            //如果需要修改Schema并且开启了batch loading模式，且没有拿到锁，就抛异常，在batch loading 模式修改schema必须获得锁
            Preconditions.checkArgument(!hasSchemaElements || (!tx.getConfiguration().hasEnabledBatchLoading() && acquireLocks),
                    "Attempting to create schema elements in inconsistent state");

            if (hasSchemaElements && !hasTxIsolation) {
                /*
                 * On storage without transactional isolation, create separate
                 * backend transaction for schema aspects to make sure that
                 * those are persisted prior to and independently of other
                 * mutations in the tx. If the storage supports transactional
                 * isolation, then don't create a separate tx.
                 */
                final BackendTransaction schemaMutator = openBackendTransaction(tx);

                try {
                    //[FAILURE] If the preparation throws an exception abort directly - nothing persisted since batch-loading cannot be enabled for schema elements
                    commitSummary = prepareCommit(addedRelations,deletedRelations, SCHEMA_FILTER, schemaMutator, tx, acquireLocks);  //
                    assert commitSummary.hasModifications && !commitSummary.has2iModifications;
                } catch (Throwable e) {
                    //Roll back schema tx and escalate exception
                    schemaMutator.rollback();
                    throw e;
                }

                try {
                    schemaMutator.commit();
                } catch (Throwable e) {
                    //[FAILURE] Primary persistence failed => abort and escalate exception, nothing should have been persisted
                    log.error("Could not commit transaction ["+transactionId+"] due to storage exception in system-commit",e);
                    throw e;
                }
            }

            //[FAILURE] Exceptions during preparation here cause the entire transaction to fail on transactional systems
            //or just the non-system part on others. Nothing has been persisted unless batch-loading
            commitSummary = prepareCommit(addedRelations,deletedRelations, hasTxIsolation? NO_FILTER : NO_SCHEMA_FILTER, mutator, tx, acquireLocks);
            if (commitSummary.hasModifications) {
                String logTxIdentifier = tx.getConfiguration().getLogIdentifier();
                boolean hasSecondaryPersistence = logTxIdentifier!=null || commitSummary.has2iModifications;

                //1. Commit storage - failures lead to immediate abort

                //1a. Add success message to tx log which will be committed atomically with all transactional changes so that we can recover secondary failures
                //    This should not throw an exception since the mutations are just cached. If it does, it will be escalated since its critical
                if (logTransaction) {
                    txLog.add(txLogHeader.serializePrimary(serializer,
                                        hasSecondaryPersistence?LogTxStatus.PRIMARY_SUCCESS:LogTxStatus.COMPLETE_SUCCESS),
                            txLogHeader.getLogKey(),mutator.getTxLogPersistor());
                }

                try {
                    mutator.commitStorage();
                } catch (Throwable e) {
                    //[FAILURE] If primary storage persistence fails abort directly (only schema could have been persisted)
                    log.error("Could not commit transaction ["+transactionId+"] due to storage exception in commit",e);
                    throw e;
                }

                if (hasSecondaryPersistence) {
                    LogTxStatus status = LogTxStatus.SECONDARY_SUCCESS;
                    Map<String,Throwable> indexFailures = ImmutableMap.of();
                    boolean userlogSuccess = true;

                    try {
                        //2. Commit indexes - [FAILURE] all exceptions are collected and logged but nothing is aborted
                        indexFailures = mutator.commitIndexes();
                        if (!indexFailures.isEmpty()) {
                            status = LogTxStatus.SECONDARY_FAILURE;
                            for (Map.Entry<String,Throwable> entry : indexFailures.entrySet()) {
                                log.error("Error while committing index mutations for transaction ["+transactionId+"] on index: " +entry.getKey(),entry.getValue());
                            }
                        }
                        //3. Log transaction if configured - [FAILURE] is recorded but does not cause exception
                        if (logTxIdentifier!=null) {
                            try {
                                userlogSuccess = false;
                                final Log userLog = backend.getUserLog(logTxIdentifier);
                                Future<Message> env = userLog.add(txLogHeader.serializeModifications(serializer, LogTxStatus.USER_LOG, tx, addedRelations, deletedRelations));
                                if (env.isDone()) {
                                    try {
                                        env.get();
                                    } catch (ExecutionException ex) {
                                        throw ex.getCause();
                                    }
                                }
                                userlogSuccess=true;
                            } catch (Throwable e) {
                                status = LogTxStatus.SECONDARY_FAILURE;
                                log.error("Could not user-log committed transaction ["+transactionId+"] to " + logTxIdentifier, e);
                            }
                        }
                    } finally {
                        if (logTransaction) {
                            //[FAILURE] An exception here will be logged and not escalated; tx considered success and
                            // needs to be cleaned up later
                            try {
                                txLog.add(txLogHeader.serializeSecondary(serializer,status,indexFailures,userlogSuccess),txLogHeader.getLogKey());
                            } catch (Throwable e) {
                                log.error("Could not tx-log secondary persistence status on transaction ["+transactionId+"]",e);
                            }
                        }
                    }
                } else {
                    //This just closes the transaction since there are no modifications
                    mutator.commitIndexes();
                }
            } else { //Just commit everything at once
                //[FAILURE] This case only happens when there are no non-system mutations in which case all changes
                //are already flushed. Hence, an exception here is unlikely and should abort
                mutator.commit();
            }
        } catch (Throwable e) {
            log.error("Could not commit transaction ["+transactionId+"] due to exception",e);
            try {
                //Clean up any left-over transaction handles
                mutator.rollback();
            } catch (Throwable e2) {
                log.error("Could not roll-back transaction ["+transactionId+"] after failure due to exception",e2);
            }
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            else throw new JanusGraphException("Unexpected exception",e);
        }
    }


    private static class ShutdownThread extends Thread {
        private final StandardJanusGraph graph;

        public ShutdownThread(StandardJanusGraph graph) {
            this.graph = graph;
        }

        @Override
        public void start() {
            log.debug("Shutting down graph {} using shutdown hook {}", graph, this);

            graph.closeInternal();
        }
    }


}
