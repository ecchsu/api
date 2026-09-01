package com.arextest.web.core.repository.mongo;

import com.arextest.web.core.repository.CaseTagUsageRepository;
import com.arextest.web.model.dao.mongodb.CaseTagUsageCollection;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CaseTagUsageRepositoryImpl implements CaseTagUsageRepository {

  private static final String APP_ID = "appId";
  private static final String USED_BYTES = "usedBytes";
  private static final String USED_COUNT = "usedCount";
  private static final String LIMIT_BYTES = "limitBytes";
  private static final String USAGE_PERCENT = "usagePercent";

  @Resource
  private MongoTemplate mongoTemplate;

  @Override
  public CaseTagUsageCollection findByAppId(String appId) {
    return mongoTemplate.findOne(Query.query(Criteria.where(APP_ID).is(appId)),
        CaseTagUsageCollection.class);
  }

  @Override
  public void incrementUsage(String appId, long deltaBytes, long deltaCount,
      long effectiveLimitBytes) {
    long now = System.currentTimeMillis();
    Query query = Query.query(Criteria.where(APP_ID).is(appId));

    // Step 1: atomic increment (and upsert on this app's first tag ever). $inc on a fixed-size
    // scalar field never changes the document's BSON size, so this stays O(1) regardless of how
    // much usage has already accumulated - see the proposal doc §6.3.3.
    Update inc = new Update()
        .inc(USED_BYTES, deltaBytes)
        .inc(USED_COUNT, deltaCount)
        .set(DATA_CHANGE_UPDATE_TIME, now)
        .setOnInsert(APP_ID, appId)
        .setOnInsert(DATA_CHANGE_CREATE_TIME, now);

    CaseTagUsageCollection updated = mongoTemplate.findAndModify(query, inc,
        FindAndModifyOptions.options().upsert(true).returnNew(true),
        CaseTagUsageCollection.class);
    if (updated == null) {
      return;
    }

    // Step 2: refresh the limit snapshot + recompute the persisted percentage. A second small
    // write, still one document, still O(1) - not proportional to batch size or historical usage.
    double usagePercent =
        effectiveLimitBytes <= 0 ? 0 : (updated.getUsedBytes() * 100.0) / effectiveLimitBytes;
    mongoTemplate.updateFirst(query,
        new Update().set(LIMIT_BYTES, effectiveLimitBytes).set(USAGE_PERCENT, usagePercent),
        CaseTagUsageCollection.class);
  }
}
