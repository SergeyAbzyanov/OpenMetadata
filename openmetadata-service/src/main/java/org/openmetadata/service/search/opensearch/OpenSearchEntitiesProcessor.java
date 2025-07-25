package org.openmetadata.service.search.opensearch;

import static org.openmetadata.service.workflows.searchIndex.ReindexingUtil.ENTITY_TYPE_KEY;
import static org.openmetadata.service.workflows.searchIndex.ReindexingUtil.getUpdatedStats;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.internal.util.ExceptionUtils;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.system.IndexingError;
import org.openmetadata.schema.system.StepStats;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.search.IndexMapping;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.SearchIndexException;
import org.openmetadata.service.util.ResultList;
import org.openmetadata.service.workflows.interfaces.Processor;
import os.org.opensearch.action.bulk.BulkRequest;
import os.org.opensearch.action.update.UpdateRequest;
import os.org.opensearch.common.xcontent.XContentType;

@Slf4j
public class OpenSearchEntitiesProcessor
    implements Processor<BulkRequest, ResultList<? extends EntityInterface>> {
  private final StepStats stats = new StepStats();

  public OpenSearchEntitiesProcessor(int total) {
    this.stats.withTotalRecords(total).withSuccessRecords(0).withFailedRecords(0);
  }

  @Override
  public BulkRequest process(
      ResultList<? extends EntityInterface> input, Map<String, Object> contextData)
      throws SearchIndexException {
    String entityType = (String) contextData.get(ENTITY_TYPE_KEY);
    if (CommonUtil.nullOrEmpty(entityType)) {
      throw new IllegalArgumentException(
          "[EsEntitiesProcessor] entityType cannot be null or empty.");
    }

    LOG.debug(
        "[EsEntitiesProcessor] Processing a Batch of Size: {}, EntityType: {} ",
        input.getData().size(),
        entityType);
    BulkRequest requests;
    try {
      requests = buildBulkRequests(entityType, input.getData());
      LOG.debug(
          "[EsEntitiesProcessor] Batch Stats :- Submitted : {} Success: {} Failed: {}",
          input.getData().size(),
          input.getData().size(),
          0);
      updateStats(input.getData().size(), 0);
    } catch (Exception e) {
      IndexingError error =
          new IndexingError()
              .withErrorSource(IndexingError.ErrorSource.PROCESSOR)
              .withSubmittedCount(input.getData().size())
              .withFailedCount(input.getData().size())
              .withSuccessCount(0)
              .withMessage(
                  "Entities Processor Encountered Failure. Converting requests to Es Request.")
              .withStackTrace(ExceptionUtils.exceptionStackTraceAsString(e));
      LOG.debug("[EsEntitiesProcessor] Failed. Details: {}", JsonUtils.pojoToJson(error));
      updateStats(0, input.getData().size());
      throw new SearchIndexException(error);
    }
    return requests;
  }

  private BulkRequest buildBulkRequests(
      String entityType, List<? extends EntityInterface> entities) {
    BulkRequest bulkRequests = new BulkRequest();
    for (EntityInterface entity : entities) {
      UpdateRequest request = getUpdateRequest(entityType, entity);
      bulkRequests.add(request);
    }
    return bulkRequests;
  }

  public static UpdateRequest getUpdateRequest(String entityType, EntityInterface entity) {
    IndexMapping indexMapping = Entity.getSearchRepository().getIndexMapping(entityType);
    UpdateRequest updateRequest =
        new UpdateRequest(
            indexMapping.getIndexName(Entity.getSearchRepository().getClusterAlias()),
            entity.getId().toString());
    updateRequest.doc(
        JsonUtils.pojoToJson(
            Objects.requireNonNull(Entity.buildSearchIndex(entityType, entity))
                .buildSearchIndexDoc()),
        XContentType.JSON);
    updateRequest.docAsUpsert(true);
    return updateRequest;
  }

  @Override
  public void updateStats(int currentSuccess, int currentFailed) {
    getUpdatedStats(stats, currentSuccess, currentFailed);
  }

  @Override
  public StepStats getStats() {
    return stats;
  }
}
