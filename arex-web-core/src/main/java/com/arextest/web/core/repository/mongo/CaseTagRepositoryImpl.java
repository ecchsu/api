package com.arextest.web.core.repository.mongo;

import com.arextest.web.core.repository.CaseTagRepository;
import com.arextest.web.model.dao.mongodb.CaseTagCollection;
import com.arextest.web.model.dao.mongodb.CaseTagCollection.Fields;
import com.arextest.web.model.dto.CaseTagDto;
import com.arextest.web.model.mapper.CaseTagMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CaseTagRepositoryImpl implements CaseTagRepository {

  private static final String APP_ID = "appId";
  private static final String PLAN_ID = "planId";
  private static final String TAG_TYPE = "tagType";
  private static final String RECORD_ID = "recordId";

  @Resource
  private MongoTemplate mongoTemplate;

  @Override
  public Set<String> queryRecordIds(String appId, String tagType, String planId) {
    Criteria criteria = Criteria.where(APP_ID).is(appId).and(TAG_TYPE).is(tagType);
    if (StringUtils.isNotEmpty(planId)) {
      criteria = criteria.and(PLAN_ID).is(planId);
    }
    Query query = Query.query(criteria);
    // minimal fetch: only need recordId
    query.fields().include(RECORD_ID);

    List<CaseTagCollection> daos = mongoTemplate.find(query, CaseTagCollection.class);
    if (CollectionUtils.isEmpty(daos)) {
      return Collections.emptySet();
    }
    return daos.stream()
        .map(CaseTagCollection::getRecordId)
        .filter(StringUtils::isNotEmpty)
        .collect(Collectors.toSet());
  }

  @Override
  public int batchAdd(List<CaseTagDto> dtos) {
    if (CollectionUtils.isEmpty(dtos)) {
      return 0;
    }
    long now = System.currentTimeMillis();
    List<CaseTagCollection> daos = dtos.stream().map(dto -> {
      dto.setDataChangeCreateTime(now);
      dto.setDataChangeUpdateTime(now);
      return CaseTagMapper.INSTANCE.daoFromDto(dto);
    }).collect(Collectors.toList());

    Collection<CaseTagCollection> inserted = mongoTemplate.insert(daos, CaseTagCollection.class);
    return inserted.size();
  }
}
