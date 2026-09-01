package com.arextest.web.model.dao.mongodb;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.mongodb.core.mapping.Document;

@EqualsAndHashCode(callSuper = true)
@Data
@FieldNameConstants
@Document(collection = "case_tag")
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
