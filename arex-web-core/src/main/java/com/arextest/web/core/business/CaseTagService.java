package com.arextest.web.core.business;

import com.arextest.web.core.business.MockTagSyncService.TagBatchResult;
import com.arextest.web.core.repository.CaseTagRepository;
import com.arextest.web.core.repository.ReplayCompareResultRepository;
import com.arextest.web.model.contract.contracts.tag.BatchAddCaseTagsByOperationRequestType;
import com.arextest.web.model.contract.contracts.tag.BatchAddCaseTagsByOperationResponseType;
import com.arextest.web.model.dto.CaseTagDto;
import com.arextest.web.model.dto.CompareResultDto;
import com.arextest.web.model.enums.DiffResultCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CaseTagService {

  public static final String TAG_PR = "pr";

  private static final List<String> CASE_SHOW_FIELDS = List.of(
      "planId", "planItemId", "recordId", "replayId", "operationName", "diffResultCode");

  @Resource
  private ReplayCompareResultRepository replayCompareResultRepository;

  @Resource
  private CaseTagRepository caseTagRepository;

  @Resource
  private MockTagSyncService mockTagSyncService;

  public BatchAddCaseTagsByOperationResponseType batchAddByOperation(
      BatchAddCaseTagsByOperationRequestType request) {
    String appId = request.getAppId();
    String tagType = request.getTagType();

    // 1. Query all compare results for this plan (select only 6 fields)
    List<CompareResultDto> cases = replayCompareResultRepository.queryCompareResults(
        request.getPlanId(), null, null, null, CASE_SHOW_FIELDS);

    // 2. Pre-load already-tagged recordIds once (avoids per-case exists() queries)
    Set<String> existingRecordIds = caseTagRepository.queryRecordIds(appId, tagType, null);

    // 3. Filter candidates: match operationName, success-only for "pr", skip duplicates
    List<CompareResultDto> candidates = new ArrayList<>();
    List<String> recordIds = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CompareResultDto c : cases) {
      if (!StringUtils.equals(request.getOperationName(), c.getOperationName())) {
        continue;
      }
      if (TAG_PR.equals(tagType) && !isSuccess(c.getDiffResultCode())) {
        continue;
      }
      String recordId = c.getRecordId();
      if (existingRecordIds.contains(recordId) || !seen.add(recordId)) {
        continue;
      }
      candidates.add(c);
      recordIds.add(recordId);
    }

    // 4. Tag mock records in Storage (source of truth) - chunked internally
    TagBatchResult tagResult = mockTagSyncService.addTagsBatch(recordIds, tagType);
    Set<String> matched = tagResult.getMatchedRecordIds();
    Map<String, Long> sizes = tagResult.getRecordSizes();

    // 5. Index only matched records (avoid orphan entries)
    List<CaseTagDto> toInsert = new ArrayList<>();
    for (CompareResultDto c : candidates) {
      if (matched.contains(c.getRecordId())) {
        toInsert.add(buildTag(appId, tagType, c, sizes.get(c.getRecordId())));
      }
    }

    int tagged = caseTagRepository.batchAdd(toInsert);
    BatchAddCaseTagsByOperationResponseType res = new BatchAddCaseTagsByOperationResponseType();
    res.setTagged(tagged);
    return res;
  }

  private boolean isSuccess(Integer diffResultCode) {
    return diffResultCode != null && diffResultCode == DiffResultCode.COMPARED_WITHOUT_DIFFERENCE;
  }

  private CaseTagDto buildTag(String appId, String tagType, CompareResultDto c,
      Long recordSizeBytes) {
    CaseTagDto dto = new CaseTagDto();
    dto.setAppId(appId);
    dto.setPlanId(c.getPlanId());
    dto.setPlanItemId(c.getPlanItemId());
    dto.setRecordId(c.getRecordId());
    dto.setReplayId(c.getReplayId());
    dto.setOperationName(c.getOperationName());
    dto.setTagType(tagType);
    dto.setCaseStatus(c.getDiffResultCode());
    dto.setRecordSizeBytes(recordSizeBytes);
    return dto;
  }
}
