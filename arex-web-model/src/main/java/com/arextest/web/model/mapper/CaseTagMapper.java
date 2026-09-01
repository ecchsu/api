package com.arextest.web.model.mapper;

import com.arextest.web.model.dao.mongodb.CaseTagCollection;
import com.arextest.web.model.dto.CaseTagDto;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface CaseTagMapper {

  CaseTagMapper INSTANCE = Mappers.getMapper(CaseTagMapper.class);

  CaseTagCollection daoFromDto(CaseTagDto dto);

  CaseTagDto dtoFromDao(CaseTagCollection dao);
}
