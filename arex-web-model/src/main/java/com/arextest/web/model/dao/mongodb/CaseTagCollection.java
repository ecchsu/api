package com.arextest.web.model.dao.mongodb;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@EqualsAndHashCode(callSuper = true)
@Data
@FieldNameConstants
@Document(collection = "case_tag")
@CompoundIndexes({
    @CompoundIndex(name = "appId_operationName", def = "{'appId': 1, 'operationName': 1}")
})
public class CaseTagCollection extends ModelBase {

  private String appId;

  private String planId;

  private String planItemId;

  private String recordId;

  private String replayId;

  private String operationName;

  private String tagType;

  private Integer caseStatus;

  private Long recordSizeBytes;
}
