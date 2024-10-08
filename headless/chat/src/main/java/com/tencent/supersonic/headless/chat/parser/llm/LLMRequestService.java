package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.pojo.enums.DataFormatTypeEnum;
import com.tencent.supersonic.common.pojo.enums.TimeDimensionEnum;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.TimeDefaultConfig;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.parser.SatisfactionChecker;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.utils.ComponentFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.parser.ParserConfig.PARSER_LINKING_VALUE_ENABLE;
import static com.tencent.supersonic.headless.chat.parser.ParserConfig.PARSER_STRATEGY_TYPE;

@Slf4j
@Service
public class LLMRequestService {
    @Autowired
    private ParserConfig parserConfig;

    public boolean isSkip(ChatQueryContext queryCtx) {
        if (!queryCtx.getText2SQLType().enableLLM()) {
            log.info("not enable llm, skip");
            return true;
        }

        if (SatisfactionChecker.isSkip(queryCtx)) {
            log.info("skip {}, queryText:{}", LLMSqlParser.class, queryCtx.getQueryText());
            return true;
        }

        return false;
    }

    public Long getDataSetId(ChatQueryContext queryCtx) {
        DataSetResolver dataSetResolver = ComponentFactory.getModelResolver();
        return dataSetResolver.resolve(queryCtx, queryCtx.getDataSetIds());
    }

    public LLMReq getLlmReq(ChatQueryContext queryCtx, Long dataSetId) {
        LLMRequestService requestService = ContextUtils.getBean(LLMRequestService.class);
        List<LLMReq.ElementValue> linkingValues = requestService.getValues(queryCtx, dataSetId);
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        Map<Long, String> dataSetIdToName = semanticSchema.getDataSetIdToName();
        String queryText = queryCtx.getQueryText();

        LLMReq llmReq = new LLMReq();

        llmReq.setQueryText(queryText);
        LLMReq.FilterCondition filterCondition = new LLMReq.FilterCondition();
        llmReq.setFilterCondition(filterCondition);

        LLMReq.LLMSchema llmSchema = new LLMReq.LLMSchema();
        llmSchema.setDataSetId(dataSetId);
        llmSchema.setDataSetName(dataSetIdToName.get(dataSetId));
        llmSchema.setDomainName(dataSetIdToName.get(dataSetId));

        Set<String> fieldNameList = getMatchedFieldNames(queryCtx, dataSetId);
        if (Objects.nonNull(semanticSchema.getDataSetSchemaMap())
                && Objects.nonNull(semanticSchema.getDataSetSchemaMap().get(dataSetId))) {
            TimeDefaultConfig timeDefaultConfig = semanticSchema.getDataSetSchemaMap()
                    .get(dataSetId).getTagTypeTimeDefaultConfig();
            if (!Objects.equals(timeDefaultConfig.getUnit(), -1)
                    && queryCtx.containsPartitionDimensions(dataSetId)) {
                // 数据集配置了数据日期字段，并查询设置 时间不为-1时才添加 '数据日期' 字段
                fieldNameList.add(TimeDimensionEnum.DAY.getChName());
            }
        }
        llmSchema.setFieldNameList(new ArrayList<>(fieldNameList));

        llmSchema.setMetrics(getMatchedMetrics(queryCtx, dataSetId));
        llmSchema.setDimensions(getMatchedDimensions(queryCtx, dataSetId));
        llmSchema.setTerms(getTerms(queryCtx, dataSetId));
        llmReq.setSchema(llmSchema);

        String priorExts = getPriorExts(queryCtx, new ArrayList<>(fieldNameList));
        llmReq.setPriorExts(priorExts);

        List<LLMReq.ElementValue> linking = new ArrayList<>();
        boolean linkingValueEnabled = Boolean.valueOf(parserConfig.getParameterValue(PARSER_LINKING_VALUE_ENABLE));

        if (linkingValueEnabled) {
            linking.addAll(linkingValues);
        }
        llmReq.setLinking(linking);

        llmReq.setCurrentDate(DateUtils.getBeforeDate(0));
        llmReq.setSqlGenType(LLMReq.SqlGenType.valueOf(parserConfig.getParameterValue(PARSER_STRATEGY_TYPE)));
        llmReq.setModelConfig(queryCtx.getModelConfig());
        llmReq.setPromptConfig(queryCtx.getPromptConfig());
        llmReq.setDynamicExemplars(queryCtx.getDynamicExemplars());

        return llmReq;
    }

    public LLMResp runText2SQL(LLMReq llmReq) {
        SqlGenStrategy sqlGenStrategy = SqlGenStrategyFactory.get(llmReq.getSqlGenType());
        String dataSet = llmReq.getSchema().getDataSetName();
        LLMResp result = sqlGenStrategy.generate(llmReq);
        result.setQuery(llmReq.getQueryText());
        result.setDataSet(dataSet);
        return result;
    }

    protected List<LLMReq.Term> getTerms(ChatQueryContext queryCtx, Long dataSetId) {
        List<SchemaElementMatch> matchedElements = queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return new ArrayList<>();
        }
        return matchedElements.stream()
                .filter(schemaElementMatch -> {
                    SchemaElementType elementType = schemaElementMatch.getElement().getType();
                    return SchemaElementType.TERM.equals(elementType);
                }).map(schemaElementMatch -> {
                    LLMReq.Term term = new LLMReq.Term();
                    term.setName(schemaElementMatch.getElement().getName());
                    term.setDescription(schemaElementMatch.getElement().getDescription());
                    term.setAlias(schemaElementMatch.getElement().getAlias());
                    return term;
                }).collect(Collectors.toList());
    }

    private String getPriorExts(ChatQueryContext queryContext, List<String> fieldNameList) {
        StringBuilder extraInfoSb = new StringBuilder();
        SemanticSchema semanticSchema = queryContext.getSemanticSchema();

        // 获取字段名到数据格式类型的映射
        Map<String, String> fieldNameToDataFormatType = semanticSchema.getMetrics().stream()
                .filter(metric -> Objects.nonNull(metric.getDataFormatType()))
                .flatMap(metric -> {
                    Set<Pair<String, String>> fieldFormatPairs = new HashSet<>();
                    String dataFormatType = metric.getDataFormatType();
                    fieldFormatPairs.add(Pair.of(metric.getName(), dataFormatType));
                    List<String> aliasList = metric.getAlias();
                    if (!CollectionUtils.isEmpty(aliasList)) {
                        aliasList.forEach(alias -> fieldFormatPairs.add(Pair.of(alias, dataFormatType)));
                    }
                    return fieldFormatPairs.stream();
                })
                .collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (existing, replacement) -> existing));

        Map<String, String> fieldNameToDateFormat = semanticSchema.getDimensions().stream()
                .filter(dimension -> StringUtils.isNotBlank(dimension.getTimeFormat()))
                .collect(Collectors.toMap(
                        SchemaElement::getName,
                        value -> Optional.ofNullable(value.getPartitionTimeFormat()).orElse(""),
                        (k1, k2) -> k1)
                );

        // 构建额外信息字符串
        for (String fieldName : fieldNameList) {
            String dataFormatType = fieldNameToDataFormatType.get(fieldName);
            if (DataFormatTypeEnum.DECIMAL.getName().equalsIgnoreCase(dataFormatType)
                    || DataFormatTypeEnum.PERCENT.getName().equalsIgnoreCase(dataFormatType)) {
                extraInfoSb.append(String.format("%s的计量单位是%s; ", fieldName, "小数"));
            }
        }
        // 构建日期格式化信息
        for (String fieldName : fieldNameList) {
            String timeFormat = fieldNameToDateFormat.get(fieldName);
            if (StringUtils.isNotBlank(timeFormat)) {
                extraInfoSb.append(String.format("%s的日期Format格式是%s; ", fieldName, timeFormat));
            }
        }
        return extraInfoSb.toString();
    }

    public List<LLMReq.ElementValue> getValues(ChatQueryContext queryCtx, Long dataSetId) {
        Map<Long, String> itemIdToName = getItemIdToName(queryCtx, dataSetId);
        List<SchemaElementMatch> matchedElements = queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return new ArrayList<>();
        }
        Set<LLMReq.ElementValue> valueMatches = matchedElements
                .stream()
                .filter(elementMatch -> !elementMatch.isInherited())
                .filter(schemaElementMatch -> {
                    SchemaElementType type = schemaElementMatch.getElement().getType();
                    return SchemaElementType.VALUE.equals(type) || SchemaElementType.ID.equals(type);
                })
                .map(elementMatch -> {
                    LLMReq.ElementValue elementValue = new LLMReq.ElementValue();
                    elementValue.setFieldName(itemIdToName.get(elementMatch.getElement().getId()));
                    elementValue.setFieldValue(elementMatch.getWord());
                    return elementValue;
                }).collect(Collectors.toSet());
        return new ArrayList<>(valueMatches);
    }

    protected Map<Long, String> getItemIdToName(ChatQueryContext queryCtx, Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        List<SchemaElement> elements = semanticSchema.getDimensions(dataSetId);
        return elements.stream().collect(
                Collectors.toMap(SchemaElement::getId, SchemaElement::getName, (value1, value2) -> value2));
    }

    protected List<SchemaElement> getMatchedMetrics(ChatQueryContext queryCtx, Long dataSetId) {
        List<SchemaElementMatch> matchedElements = queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return Collections.emptyList();
        }
        List<SchemaElement> schemaElements = matchedElements.stream()
                .filter(schemaElementMatch -> {
                    SchemaElementType elementType = schemaElementMatch.getElement().getType();
                    return SchemaElementType.METRIC.equals(elementType);
                })
                .map(schemaElementMatch -> {
                    return schemaElementMatch.getElement();
                })
                .collect(Collectors.toList());
        return schemaElements;
    }

    protected List<SchemaElement> getMatchedDimensions(ChatQueryContext queryCtx, Long dataSetId) {
        List<SchemaElementMatch> matchedElements = queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return Collections.emptyList();
        }
        return matchedElements.stream()
                .filter(schemaElementMatch -> {
                    SchemaElementType elementType = schemaElementMatch.getElement().getType();
                    return SchemaElementType.DIMENSION.equals(elementType);
                })
                .map(schemaElementMatch -> schemaElementMatch.getElement())
                .collect(Collectors.toList());
    }

    protected Set<String> getMatchedFieldNames(ChatQueryContext queryCtx, Long dataSetId) {
        Map<Long, String> itemIdToName = getItemIdToName(queryCtx, dataSetId);
        List<SchemaElementMatch> matchedElements = queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return new HashSet<>();
        }
        return matchedElements.stream()
                .filter(schemaElementMatch -> {
                    SchemaElementType elementType = schemaElementMatch.getElement().getType();
                    return SchemaElementType.METRIC.equals(elementType)
                            || SchemaElementType.DIMENSION.equals(elementType)
                            || SchemaElementType.VALUE.equals(elementType);
                })
                .map(schemaElementMatch -> {
                    SchemaElement element = schemaElementMatch.getElement();
                    SchemaElementType elementType = element.getType();
                    if (SchemaElementType.VALUE.equals(elementType)) {
                        return itemIdToName.get(element.getId());
                    }
                    return schemaElementMatch.getWord();
                })
                .collect(Collectors.toSet());
    }
}
