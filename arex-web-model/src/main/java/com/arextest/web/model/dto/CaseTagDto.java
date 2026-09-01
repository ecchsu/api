package com.arextest.web.model.dto;

import lombok.Data;

@Data
public class CaseTagDto extends BaseDto {

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
