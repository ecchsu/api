package com.arextest.web.core.business;

import com.arextest.web.common.LogUtils;
import com.arextest.web.core.business.beans.httpclient.HttpWebServiceApiClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Writes tags onto mock records held by the AREX Storage service (the source of truth). The web
 * DB's case_tag collection is only a secondary index over whatever Storage actually confirms.
 */
@Slf4j
@Component
public class MockTagSyncService {

  private static final String TAG_VALUE_TRUE = "true";
  private static final int RECORD_IDS_CHUNK_SIZE = 100;

  @Value("${arex.storage.update.tags.add.batch.url}")
  private String addTagsBatchUrl;

  @Resource
  private HttpWebServiceApiClient httpWebServiceApiClient;

  public TagBatchResult addTagsBatch(List<String> recordIds, String tagKey) {
    if (recordIds == null || recordIds.isEmpty() || StringUtils.isEmpty(tagKey)) {
      return new TagBatchResult(Collections.emptySet(), Collections.emptyMap());
    }

    Set<String> matchedRecordIds = new HashSet<>();
    Map<String, Long> recordSizes = new HashMap<>();

    // Chunking: split into batches to avoid long-running / oversized requests
    for (int from = 0; from < recordIds.size(); from += RECORD_IDS_CHUNK_SIZE) {
      int to = Math.min(from + RECORD_IDS_CHUNK_SIZE, recordIds.size());
      List<String> chunk = recordIds.subList(from, to);

      UpdateTagsBatchRequest request = new UpdateTagsBatchRequest();
      request.setRecordIds(new ArrayList<>(chunk));
      request.setTagKey(tagKey);
      request.setTagValue(TAG_VALUE_TRUE);

      AddTagsBatchResponse response = httpWebServiceApiClient.post(addTagsBatchUrl, request,
          AddTagsBatchResponse.class);

      if (response == null || response.getMatchedRecordIds() == null) {
        // Failed chunk: skip it, don't abort the whole operation
        LogUtils.error(LOGGER,
            String.format("addTagsBatch storage call failed, %d recordIds (chunk %d-%d of %d), tag:%s",
                chunk.size(), from, to, recordIds.size(), tagKey));
        continue;
      }
      matchedRecordIds.addAll(response.getMatchedRecordIds());
      if (response.getRecordSizes() != null) {
        recordSizes.putAll(response.getRecordSizes());
      }
    }
    return new TagBatchResult(matchedRecordIds, recordSizes);
  }

  @Data
  public static class UpdateTagsBatchRequest {

    private List<String> recordIds;
    private String tagKey;
    private String tagValue;
  }

  @Data
  public static class AddTagsBatchResponse {

    // records that exist in storage
    private List<String> matchedRecordIds;
    // BSON size per matched record
    private Map<String, Long> recordSizes;
  }

  @Getter
  @AllArgsConstructor
  public static class TagBatchResult {

    private final Set<String> matchedRecordIds;
    private final Map<String, Long> recordSizes;
  }
}
