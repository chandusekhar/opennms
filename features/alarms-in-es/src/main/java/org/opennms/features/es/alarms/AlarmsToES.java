/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.es.alarms;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.opennms.core.cache.Cache;
import org.opennms.core.cache.CacheBuilder;
import org.opennms.core.cache.CacheConfig;
import org.opennms.core.time.PseudoClock;
import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.NodeDocumentDTO;
import org.opennms.features.es.alarms.tasks.BulkDeleteTask;
import org.opennms.features.es.alarms.tasks.DeleteAlarmTask;
import org.opennms.features.es.alarms.tasks.IndexAlarmsTask;
import org.opennms.features.es.alarms.tasks.Task;
import org.opennms.features.es.alarms.tasks.TaskVisitor;
import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.plugins.elasticsearch.rest.bulk.BulkException;
import org.opennms.plugins.elasticsearch.rest.bulk.BulkRequest;
import org.opennms.plugins.elasticsearch.rest.bulk.BulkWrapper;
import org.opennms.plugins.elasticsearch.rest.bulk.FailedItem;
import org.opennms.plugins.elasticsearch.rest.executors.LimitedRetriesRequestExecutor;
import org.opennms.plugins.elasticsearch.rest.index.IndexStrategy;
import org.opennms.plugins.elasticsearch.rest.template.TemplateInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;

import io.searchbox.client.JestClient;
import io.searchbox.core.Bulk;
import io.searchbox.core.Index;
import io.searchbox.core.UpdateByQuery;
import io.searchbox.core.UpdateByQueryResult;

/**
 * TODO: Handle different prefixes i.e. many OpenNMS systems using the same ES
 */
public class AlarmsToES implements AlarmLifecycleListener, Runnable  {

    private static final Logger LOG = LoggerFactory.getLogger(AlarmsToES.class);
    private static final Gson gson = new Gson();

    private final JestClient client;
    private final TemplateInitializer templateInitializer;
    private IndexStrategy indexStrategy = IndexStrategy.MONTHLY;
    private int bulkRetryCount = 3;
    private int batchSize = 200;
    private final String alarmIndexPrefix = "opennms-alarms";
    private boolean usePseudoClock = false;

    /**
     * Duration of time in milliseconds at which the alarms should be reindexed,
     * provided that they are still present, and have not been indexed whithin this past
     * duration.
     *
     * This allows the documents to reflect the actual state, even if no "intersesting"
     * fields have changed.
     *
     * TODO: Make configurable
     **/
    private long alarmReindexDurationMs = TimeUnit.HOURS.toMillis(1);

    private final QueryProvider queryProvider = new QueryProvider();
    private LimitedRetriesRequestExecutor limitedRetriesRequestExecutor;

    private final List<AlarmDocumentDTO> alarmDocumentsToIndex = new LinkedList<>();

    private Map<Integer, AlarmDocumentDTO> alarmDocumentsById = new LinkedHashMap<>();
    private final BlockingQueue<Task> taskQueue = new ArrayBlockingQueue<>(10000, true);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
            .setNameFormat("alarms-to-es")
            .build());
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private java.util.Timer timer;
    private final DocumentMapper documentMapper;

    private final Cache<Integer, Optional<NodeDocumentDTO>> nodeInfoCache;
    private final AlarmsToESMetrics alarmsToESMetrics;

    public AlarmsToES(MetricRegistry metrics, JestClient client, TemplateInitializer templateInitializer) {
        this(metrics, client, templateInitializer, new CacheConfig("nodes-for-alarms-in-es"));
    }

    public AlarmsToES(MetricRegistry metrics, JestClient client, TemplateInitializer templateInitializer, CacheConfig nodeCacheConfig) {
        this.client = Objects.requireNonNull(client);
        this.templateInitializer = Objects.requireNonNull(templateInitializer);
        nodeInfoCache = new CacheBuilder<>()
                .withConfig(nodeCacheConfig)
                .withCacheLoader(new CacheLoader<Integer, Optional<NodeDocumentDTO>>() {
                    @Override
                    public Optional<NodeDocumentDTO> load(Integer nodeId) {
                        return Optional.empty();
                    }
                }).build();
        alarmsToESMetrics = new AlarmsToESMetrics(metrics, taskQueue);
        documentMapper = new DocumentMapper(nodeInfoCache, this::getCurrentTimeMillis);
    }

    public void init() {
        if (stopped.get()) {
            throw new IllegalStateException("Already destroyed.");
        }
        executor.execute(this);
        timer = new java.util.Timer("alarms-to-es");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    flushDocumentsToIndexToTaskQueue();
                } catch (Exception e) {
                    LOG.error("Flush failed.", e);
                }
            }
        }, 500, 500); // TODO: Make period configurable
        // TODO: Make timeout configurable
        limitedRetriesRequestExecutor = new LimitedRetriesRequestExecutor(5000,  bulkRetryCount);
    }

    public void destroy() {
        stopped.set(true);
        timer.cancel();
        executor.shutdown();
    }

    private Collection<String> getIndicesForDeleteAt(long time) {
        // TODO: Add proper logic
        return Collections.singletonList(indexStrategy.getIndex(alarmIndexPrefix, Instant.ofEpochMilli(time)));
    }

    @Override
    public void run() {
        templateInitializer.initialize();
        while(!stopped.get()) {
            try {
                final Task task = taskQueue.take();
                task.visit(new TaskVisitor() {
                    @Override
                    public void indexAlarms(List<AlarmDocumentDTO> docs) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Indexing documents for alarms with ids: {}", docs.stream().map(AlarmDocumentDTO::getId).collect(Collectors.toList()));
                        }
                        try (final Timer.Context ctx = alarmsToESMetrics.getBulkIndexTimer().time()) {
                            bulkInsert(docs);
                            LOG.debug("Successfully indexed {} documents.", docs.size());
                            alarmsToESMetrics.getBulkIndexSizeHistogram().update(docs.size());
                        } catch (PersistenceException|IOException e) {
                            LOG.error("Indexing {} documents failed. These documents will be lost.", docs.size(), e);
                            alarmsToESMetrics.getTasksFailedCounter().inc();
                        }
                    }

                    @Override
                    public void deleteAlarm(int alarmId, long time) {
                        LOG.debug("Marking document related to alarm with id: {} deleted with time: {}", alarmId, time);
                        // TODO: Should only be applied to documents created before the given time and not yet marked
                        final String query = queryProvider.markAlarmAsDeleted(alarmId, time);
                        final Collection<String> indices = getIndicesForDeleteAt(time);
                        final UpdateByQuery updateByQuery = new UpdateByQuery.Builder(query)
                                .addIndices(getIndicesForDeleteAt(time))
                                .setParameter("conflicts", "proceed")
                                .addType(AlarmDocumentDTO.TYPE)
                                .build();

                        try (final Timer.Context ctx = alarmsToESMetrics.getDeleteTimer().time()) {
                            final UpdateByQueryResult result = limitedRetriesRequestExecutor.execute(client, updateByQuery);
                            if (!result.isSucceeded()) {
                                LOG.error("Delete for alarm with id: {} failed with: {}", alarmId, result.getErrorMessage());
                            } else if (result.getUpdatedCount() < 1) {
                                LOG.warn("Did not find any documents for alarm with id: {} to delete in indices: {}.",
                                        alarmId, indices);
                            } else {
                                LOG.debug("Successfully marked documents for alarm with id: {} as deleted.", alarmId);
                            }
                        } catch (IOException e) {
                            LOG.error("Delete for alarm with id: {} failed.", alarmId, e);
                            alarmsToESMetrics.getTasksFailedCounter().inc();
                        }
                    }

                    @Override
                    public void deleteAlarmsWithoutIdsIn(Set<Integer> alarmIdsToKeep, long time) {
                        LOG.debug("Marking documents without ids in: {} as deleted for time: {}", alarmIdsToKeep, time);
                        // TODO: Should only be applied to documents created before the given time and not yet marked
                        final String query = queryProvider.markOtherAlarmsAsDeleted(alarmIdsToKeep, time);
                        final UpdateByQuery updateByQuery = new UpdateByQuery.Builder(query)
                                .addIndices(getIndicesForDeleteAt(time))
                                .setParameter("conflicts", "proceed")
                                .addType(AlarmDocumentDTO.TYPE)
                                .build();

                        try (final Timer.Context ctx = alarmsToESMetrics.getBulkDeleteTimer().time()) {
                            final UpdateByQueryResult result = limitedRetriesRequestExecutor.execute(client, updateByQuery);
                            if (!result.isSucceeded()) {
                                LOG.error("Bulk delete failed with: {}", result.getErrorMessage());
                            } else {
                                LOG.debug("Marked {} documents as deleted.", result.getUpdatedCount());
                            }
                        } catch (IOException e) {
                            LOG.error("Bulk delete failed.", e);
                            alarmsToESMetrics.getTasksFailedCounter().inc();
                        }
                    }
                });
            } catch (InterruptedException e) {
                LOG.info("Interrupted. Stopping.");
                return;
            } catch (Exception e) {
                LOG.error("Handling of task failed.", e);
                alarmsToESMetrics.getTasksFailedCounter().inc();
            }
        }
    }

    public void bulkInsert(List<AlarmDocumentDTO> alarmDocuments) throws PersistenceException, IOException {
        final BulkRequest<AlarmDocumentDTO> bulkRequest = new BulkRequest<>(client, alarmDocuments, (documents) -> {
            final Bulk.Builder bulkBuilder = new Bulk.Builder();
            for (AlarmDocumentDTO alarmDocument : alarmDocuments) {
                final String index = indexStrategy.getIndex(alarmIndexPrefix, Instant.ofEpochMilli(alarmDocument.getUpdateTime()));
                final Index.Builder indexBuilder = new Index.Builder(alarmDocument)
                        .index(index)
                        .type(AlarmDocumentDTO.TYPE);
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Adding index action on index: {} with type: {} and payload: {}",
                            index, AlarmDocumentDTO.TYPE, gson.toJson(alarmDocument));
                }
                bulkBuilder.addAction(indexBuilder.build());
            }
            return new BulkWrapper(bulkBuilder);
        }, bulkRetryCount);

        try {
            // the bulk request considers retries
            bulkRequest.execute();
        } catch (BulkException ex) {
            final List<FailedItem<AlarmDocumentDTO>> failedItems;
            if (ex.getBulkResult() != null) {
                failedItems = ex.getBulkResult().getFailedItems();
            } else {
                failedItems = Collections.emptyList();
            }
            throw new PersistenceException(ex.getMessage(), failedItems);
        } catch (IOException ex) {
            LOG.error("An error occurred while executing the given request: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    // TODO: Remove synchronized safely?
    @Override
    public synchronized void handleAlarmSnapshot(List<OnmsAlarm> alarms) {
        LOG.debug("Got snapshot with {} alarms.", alarms.size());
        flushDocumentsToIndexToTaskQueue();
        // Index/update documents as necessary
        final List<AlarmDocumentDTO> alarmDocuments = alarms.stream()
                .map(this::getDocumentIfNeedsIndexing)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (alarmDocuments.size() > 0) {
            // Break the list up into small batches limited by the configured batch size
            for (List<AlarmDocumentDTO> partition : Lists.partition(alarmDocuments, batchSize)) {
                taskQueue.add(new IndexAlarmsTask(partition));
            }
        }

        // Bulk delete alarms that are not yet marked as deleted in ES, and are not present in the given list
        final Set<Integer> alarmIds = alarms.stream().map(OnmsAlarm::getId)
                .collect(Collectors.toSet());
        taskQueue.add(new BulkDeleteTask(alarmIds, getCurrentTimeMillis()));
        alarmDocumentsById.keySet().removeIf(alarmId -> !alarmIds.contains(alarmId));
    }

    @Override
    public synchronized void handleNewOrUpdatedAlarm(OnmsAlarm alarm) {
        LOG.debug("Got new or updated alarm callback for alarm with id: {} and reduction key: {}",
                alarm.getId(), alarm.getReductionKey());
        final AlarmDocumentDTO alarmDocument = getDocumentIfNeedsIndexing(alarm);
        if (alarmDocument != null) {
            alarmDocumentsToIndex.add(alarmDocument);
            if (alarmDocumentsToIndex.size() >= batchSize) {
                flushDocumentsToIndexToTaskQueue();
            }
        }
    }

    @Override
    public synchronized void handleDeletedAlarm(int alarmId, String reductionKey) {
        LOG.debug("Got delete callback for alarm with id: {} and reduction key: {}",
                alarmId, reductionKey);
        flushDocumentsToIndexToTaskQueue();
        taskQueue.add(new DeleteAlarmTask(alarmId, getCurrentTimeMillis()));
        alarmDocumentsById.remove(alarmId);
    }

    private synchronized void flushDocumentsToIndexToTaskQueue() {
        if (alarmDocumentsToIndex.size() > 0) {
            taskQueue.add(new IndexAlarmsTask(new ArrayList<>(alarmDocumentsToIndex)));
            alarmDocumentsToIndex.clear();
        }
    }

    private AlarmDocumentDTO getDocumentIfNeedsIndexing(OnmsAlarm alarm) {
        final AlarmDocumentDTO existingAlarmDocument = alarmDocumentsById.get(alarm.getId());

        // These conditions could be combined in a single mega-conditional
        // but I find it easier to follow if kept separated
        boolean needsIndexing = false;
        if (existingAlarmDocument == null) {
            needsIndexing = true;
        } else if (getCurrentTimeMillis() - existingAlarmDocument.getUpdateTime() >= alarmReindexDurationMs) {
            needsIndexing = true;
        } else if (!Objects.equals(existingAlarmDocument.getReductionKey(), alarm.getReductionKey())
                || !Objects.equals(existingAlarmDocument.getAckTime(), alarm.getAckTime() != null ? alarm.getAckTime().getTime() : null)
                || !Objects.equals(existingAlarmDocument.getSeverityId(), alarm.getSeverity().getId())
                || !Objects.equals(new HashSet<>(existingAlarmDocument.getRelatedAlarmIds()), alarm.getRelatedAlarmIds())) {
            // TODO: Update list of "interesting" fields
            // sticky + journal memos
            needsIndexing = true;
        }

        if (!needsIndexing) {
            return null;
        }

        final AlarmDocumentDTO doc = documentMapper.toDocument(alarm);
        alarmDocumentsById.put(alarm.getId(), doc);
        return doc;
    }

    private long getCurrentTimeMillis() {
        if (usePseudoClock) {
            return PseudoClock.getInstance().getTime();
        } else {
            return System.currentTimeMillis();
        }
    }

    public void setBulkRetryCount(int bulkRetryCount) {
        this.bulkRetryCount = bulkRetryCount;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setIndexStrategy(IndexStrategy indexStrategy) {
        this.indexStrategy = indexStrategy;
    }

    public void setUsePseudoClock(boolean usePseudoClock) {
        this.usePseudoClock = usePseudoClock;
    }


}