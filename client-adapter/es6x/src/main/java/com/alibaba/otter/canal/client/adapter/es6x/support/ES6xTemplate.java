package com.alibaba.otter.canal.client.adapter.es6x.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.otter.canal.client.adapter.es.core.config.ESSyncConfig;
import com.alibaba.otter.canal.client.adapter.es.core.config.ESSyncConfig.ESMapping;
import com.alibaba.otter.canal.client.adapter.es.core.config.SchemaItem;
import com.alibaba.otter.canal.client.adapter.es.core.config.SchemaItem.ColumnItem;
import com.alibaba.otter.canal.client.adapter.es.core.config.SchemaItem.FieldItem;
import com.alibaba.otter.canal.client.adapter.es.core.support.ESBulkRequest;
import com.alibaba.otter.canal.client.adapter.es.core.support.ESBulkRequest.*;
import com.alibaba.otter.canal.client.adapter.es.core.support.ESSyncUtil;
import com.alibaba.otter.canal.client.adapter.es.core.support.ESTemplate;
import com.alibaba.otter.canal.client.adapter.es6x.support.ESConnection.ESSearchRequest;
import com.alibaba.otter.canal.client.adapter.support.DatasourceConfig;
import com.alibaba.otter.canal.client.adapter.support.Util;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ES6xTemplate implements ESTemplate {

    private static final Logger                               logger         = LoggerFactory
        .getLogger(ESTemplate.class);

    private static final int                                  MAX_BATCH_SIZE = 1000;

    private ESConnection                                      esConnection;

    private ESBulkRequest                                     esBulkRequest;

    // es 字段类型本地缓存
    private static ConcurrentMap<String, Map<String, Object>> esFieldTypes   = new ConcurrentHashMap<>();

    public ES6xTemplate(ESConnection esConnection){
        this.esConnection = esConnection;
        this.esBulkRequest = this.esConnection.new ES6xBulkRequest();
    }

    public ESBulkRequest getBulk() {
        return esBulkRequest;
    }

    public void resetBulkRequestBuilder() {
        this.esBulkRequest.resetBulk();
    }

    /**
     * 加入批
     */
    private void addToBulk(IESRequest esRequest, ESMapping mapping, Map<String, Object> esFieldData) {
        getBulk().add(esRequest, mapping.getCommitBatchSize(), bytesSizeToAdd -> {
            if (getBulk().numberOfActions() <= 0) {
                try {
                    getBulk().add(esRequest);
                    commit();
                } catch (Exception e) {
                    logger.error("批次中单条数据已达上限, 尝试单独推送失败！" + JSON.toJSONString(esFieldData), e);
                    throw e;
                }
                return false;
            }

            //提交后可添加新的进去
            commit();
            return true;
        });
    }

    @Override
    public void insert(ESSyncConfig.ESMapping mapping, Object pkVal, Map<String, Object> esFieldData) {
        if (mapping.get_id() != null) {
            String parentVal = (String) esFieldData.remove("$parent_routing");
            if (mapping.isUpsert()) {
                ESUpdateRequest updateRequest = esConnection.new ES6xUpdateRequest(mapping.get_index(),
                    mapping.get_type(),
                    pkVal.toString()).setDoc(esFieldData).setDocAsUpsert(true);
                if (StringUtils.isNotEmpty(parentVal)) {
                    updateRequest.setRouting(parentVal);
                }
                addToBulk(updateRequest, mapping, esFieldData);
            } else {
                ESIndexRequest indexRequest = esConnection.new ES6xIndexRequest(mapping.get_index(),
                    mapping.get_type(),
                    pkVal.toString()).setSource(esFieldData);
                if (StringUtils.isNotEmpty(parentVal)) {
                    indexRequest.setRouting(parentVal);
                }
                addToBulk(indexRequest, mapping, esFieldData);
            }
            commitBulk();
        } else {
            ESSearchRequest esSearchRequest = this.esConnection.new ESSearchRequest(mapping.get_index(),
                mapping.get_type()).setQuery(QueryBuilders.termQuery(mapping.getPk(), pkVal)).size(10000);
            SearchResponse response = esSearchRequest.getResponse();

            for (SearchHit hit : response.getHits()) {
                ESUpdateRequest esUpdateRequest = this.esConnection.new ES6xUpdateRequest(mapping.get_index(),
                    mapping.get_type(),
                    hit.getId()).setDoc(esFieldData);
                addToBulk(esUpdateRequest, mapping, esFieldData);
                commitBulk();
            }
        }
    }

    @Override
    public void update(ESSyncConfig.ESMapping mapping, Object pkVal, Map<String, Object> esFieldData) {
        Map<String, Object> esFieldDataTmp = new LinkedHashMap<>(esFieldData.size());
        esFieldData.forEach((k, v) -> esFieldDataTmp.put(Util.cleanColumn(k), v));
        append4Update(mapping, pkVal, esFieldDataTmp);
        commitBulk();
    }

    @Override
    public void updateByQuery(ESSyncConfig config, Map<String, Object> paramsTmp, Map<String, Object> esFieldData) {
        if (paramsTmp.isEmpty()) {
            return;
        }
        ESMapping mapping = config.getEsMapping();
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        paramsTmp.forEach((fieldName, value) -> queryBuilder.must(QueryBuilders.termsQuery(fieldName, value)));

        // 查询sql批量更新
        DataSource ds = DatasourceConfig.DATA_SOURCES.get(config.getDataSourceKey());
        StringBuilder sql = new StringBuilder("SELECT * FROM (" + mapping.getSql() + ") _v WHERE ");
        List<Object> values = new ArrayList<>();
        paramsTmp.forEach((fieldName, value) -> {
            sql.append("_v.").append(fieldName).append("=? AND ");
            values.add(value);
        });
        // TODO 直接外部包裹sql会导致全表扫描性能低, 待优化拼接内部where条件
        int len = sql.length();
        sql.delete(len - 4, len);
        Integer syncCount = (Integer) Util.sqlRS(ds, sql.toString(), values, rs -> {
            int count = 0;
            try {
                while (rs.next()) {
                    Object idVal = getIdValFromRS(mapping, rs);
                    append4Update(mapping, idVal, esFieldData);
                    commitBulk();
                    count++;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return count;
        });
        if (logger.isTraceEnabled()) {
            logger.trace("Update ES by query affected {} records", syncCount);
        }
    }

    @Override
    public void delete(ESSyncConfig.ESMapping mapping, Object pkVal, Map<String, Object> esFieldData) {
        if (mapping.get_id() != null) {
            ESDeleteRequest esDeleteRequest = this.esConnection.new ES6xDeleteRequest(mapping.get_index(),
                mapping.get_type(),
                pkVal.toString());
            addToBulk(esDeleteRequest, mapping, esFieldData);
            commitBulk();
        } else {
            ESSearchRequest esSearchRequest = this.esConnection.new ESSearchRequest(mapping.get_index(),
                mapping.get_type()).setQuery(QueryBuilders.termQuery(mapping.getPk(), pkVal)).size(10000);
            SearchResponse response = esSearchRequest.getResponse();
            for (SearchHit hit : response.getHits()) {
                ESUpdateRequest esUpdateRequest = this.esConnection.new ES6xUpdateRequest(mapping.get_index(),
                    mapping.get_type(),
                    hit.getId()).setDoc(esFieldData);
                addToBulk(esUpdateRequest, mapping, esFieldData);
                commitBulk();
            }
        }
    }

    @Override
    public void commit() {
        if (getBulk().numberOfActions() > 0) {
            ESBulkResponse response = getBulk().bulk();
            if (response.hasFailures()) {
                response.processFailBulkResponse("ES sync commit error ");
            }
            resetBulkRequestBuilder();
        }
    }

    @Override
    public Object getValFromRS(ESSyncConfig.ESMapping mapping, ResultSet resultSet, String fieldName,
                               String columnName) throws SQLException {
        fieldName = Util.cleanColumn(fieldName);
        columnName = Util.cleanColumn(columnName);
        String esType = getEsType(mapping, fieldName);

        Object value = resultSet.getObject(columnName);
        if (value instanceof Boolean) {
            if (!"boolean".equals(esType)) {
                value = resultSet.getByte(columnName);
            }
        }

        return convertEsObj(mapping, fieldName, esType, value);
    }

    private Object convertEsObj(ESMapping mapping, String fieldName, String esType, Object value) {
        Map<String, ESSyncConfig.ObjField> objFields = mapping.getObjFields().getFields();

        // 如果是对象类型
        if ((!CollectionUtils.isEmpty(objFields)) && objFields.containsKey(fieldName)) {
            ESSyncConfig.ObjField objField = objFields.get(fieldName);

            if (StringUtils.isBlank(objField.getSql())) {

                String separator = objField.getSeparator();
                ESSyncConfig.ObjFieldType type = objField.getType();
                String expr = StringUtils.isNotEmpty(separator) ? type.name() + ":" + separator : type.name();

                return ESSyncUtil.convertToEsObj(value, expr);

            } else {
                //有子表sql的对象字段不在这里获取值
                return null;
            }
        }

        return ESSyncUtil.typeConvert(value, esType);
    }

    @Override
    public Object getESDataFromRS(ESSyncConfig.ESMapping mapping, ResultSet resultSet,
                                  Map<String, Object> esFieldData) throws SQLException {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            Object value = getValFromRS(mapping, resultSet, fieldItem.getFieldName(), fieldItem.getFieldName());

            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = value;
            }

            if (!fieldItem.getFieldName().equals(mapping.get_id())
                    && !mapping.getSkips().contains(fieldItem.getFieldName())) {
                esFieldData.put(Util.cleanColumn(fieldItem.getFieldName()), value);
            }
        }

        // 添加父子文档关联信息
        putRelationDataFromRS(mapping, schemaItem, resultSet, esFieldData);

        return resultIdVal;
    }

    @Override
    public Object getIdValFromRS(ESSyncConfig.ESMapping mapping, ResultSet resultSet) throws SQLException {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            Object value = getValFromRS(mapping, resultSet, fieldItem.getFieldName(), fieldItem.getFieldName());

            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = value;
                break;
            }
        }
        return resultIdVal;
    }

    @Override
    public Object getESDataFromRS(ESSyncConfig.ESMapping mapping, ResultSet resultSet, Map<String, Object> dmlOld,
                                  Map<String, Object> esFieldData) throws SQLException {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = getValFromRS(mapping, resultSet, fieldItem.getFieldName(), fieldItem.getFieldName());
            }

            for (ColumnItem columnItem : fieldItem.getColumnItems()) {
                if (dmlOld.containsKey(columnItem.getColumnName())
                        && !mapping.getSkips().contains(fieldItem.getFieldName())) {
                    esFieldData.put(Util.cleanColumn(fieldItem.getFieldName()),
                            getValFromRS(mapping, resultSet, fieldItem.getFieldName(), fieldItem.getFieldName()));
                    break;
                }
            }
        }

        // 添加父子文档关联信息
        putRelationDataFromRS(mapping, schemaItem, resultSet, esFieldData);

        return resultIdVal;
    }

    @Override
    public Object getValFromData(ESSyncConfig.ESMapping mapping, Map<String, Object> dmlData, String fieldName,
                                 String columnName) {
        String esType = getEsType(mapping, fieldName);
        Object value = dmlData.get(columnName);
        if (value instanceof Byte) {
            if ("boolean".equals(esType)) {
                value = ((Byte) value).intValue() != 0;
            }
        }

        return convertEsObj(mapping, fieldName, esType, value);
    }

    @Override
    public Object getESDataFromDmlData(ESSyncConfig.ESMapping mapping, Map<String, Object> dmlData,
                                       Map<String, Object> esFieldData) {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            String columnName = fieldItem.getColumnItems().iterator().next().getColumnName();
            Object value = getValFromData(mapping, dmlData, fieldItem.getFieldName(), columnName);

            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = value;
            }

            if (!fieldItem.getFieldName().equals(mapping.get_id())
                    && !mapping.getSkips().contains(fieldItem.getFieldName())) {
                esFieldData.put(Util.cleanColumn(fieldItem.getFieldName()), value);
            }
        }

        // 添加父子文档关联信息
        putRelationData(mapping, schemaItem, dmlData, esFieldData);
        return resultIdVal;
    }

    @Override
    public Object getESDataFromDmlData(ESSyncConfig.ESMapping mapping, Map<String, Object> dmlData,
                                       Map<String, Object> dmlOld, Map<String, Object> esFieldData) {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            String columnName = fieldItem.getColumnItems().iterator().next().getColumnName();

            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = getValFromData(mapping, dmlData, fieldItem.getFieldName(), columnName);
            }

            if (dmlOld.containsKey(columnName) && !mapping.getSkips().contains(fieldItem.getFieldName())) {
                esFieldData.put(Util.cleanColumn(fieldItem.getFieldName()),
                        getValFromData(mapping, dmlData, fieldItem.getFieldName(), columnName));
            }
        }

        // 添加父子文档关联信息
        putRelationData(mapping, schemaItem, dmlOld, esFieldData);
        return resultIdVal;
    }

    /**
     * 如果大于批量数则提交批次
     */
    private void commitBulk() {
        if (getBulk().numberOfActions() >= MAX_BATCH_SIZE) {
            commit();
        }
    }

    private void append4Update(ESMapping mapping, Object pkVal, Map<String, Object> esFieldData) {
        if (mapping.get_id() != null) {
            String parentVal = (String) esFieldData.remove("$parent_routing");
            if (mapping.isUpsert()) {
                ESUpdateRequest esUpdateRequest = this.esConnection.new ES6xUpdateRequest(mapping.get_index(),
                    mapping.get_type(),
                    pkVal.toString()).setDoc(esFieldData).setDocAsUpsert(true);
                if (StringUtils.isNotEmpty(parentVal)) {
                    esUpdateRequest.setRouting(parentVal);
                }
                addToBulk(esUpdateRequest, mapping, esFieldData);
            } else {
                ESUpdateRequest esUpdateRequest = this.esConnection.new ES6xUpdateRequest(mapping.get_index(),
                    mapping.get_type(),
                    pkVal.toString()).setDoc(esFieldData);
                if (StringUtils.isNotEmpty(parentVal)) {
                    esUpdateRequest.setRouting(parentVal);
                }
                addToBulk(esUpdateRequest, mapping, esFieldData);
            }
        } else {
            ESSearchRequest esSearchRequest = this.esConnection.new ESSearchRequest(mapping.get_index(),
                mapping.get_type()).setQuery(QueryBuilders.termQuery(mapping.getPk(), pkVal)).size(10000);
            SearchResponse response = esSearchRequest.getResponse();
            for (SearchHit hit : response.getHits()) {
                ESUpdateRequest esUpdateRequest = this.esConnection.new ES6xUpdateRequest(mapping.get_index(),
                    mapping.get_type(),
                    hit.getId()).setDoc(esFieldData);
                addToBulk(esUpdateRequest, mapping, esFieldData);
            }
        }
    }

    /**
     * 获取es mapping中的属性类型
     *
     * @param mapping   mapping配置
     * @param fieldName 属性名
     * @return 类型
     */
    public String getEsType(ESMapping mapping, String fieldName) {
        Object esType = getEsMapping(mapping).get(fieldName);
        return esType instanceof Map ? "object" : Objects.toString(esType, null);
    }

    /**
     * 获取es mapping中的子属性类型
     *
     * @param mapping           mapping配置
     * @param fieldName         属性名
     * @param childFieldName    子属性名
     * @return 类型
     */
    public String getEsType(ESMapping mapping, String fieldName, String childFieldName) {
        Object esType = getEsMapping(mapping).get(fieldName);
        if (esType instanceof Map) {
            return Objects.toString(((Map) esType).get(childFieldName), null);
        }
        return Objects.toString(esType, null);
    }

    /**
     * 获取es mapping信息
     * @param mapping es配置信息
     * @return
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getEsMapping(ESMapping mapping) {
        String key = mapping.get_index() + "-" + mapping.get_type();
        Map<String, Object> fieldType = esFieldTypes.get(key);
        if (fieldType == null) {
            MappingMetaData mappingMetaData = esConnection.getMapping(mapping.get_index(), mapping.get_type());

            if (mappingMetaData == null) {
                throw new IllegalArgumentException("Not found the mapping info of index: " + mapping.get_index());
            }

            fieldType = new LinkedHashMap<>();

            Map<String, Object> sourceMap = mappingMetaData.getSourceAsMap();
            Map<String, Object> esMapping = (Map<String, Object>) sourceMap.get("properties");
            for (Map.Entry<String, Object> entry : esMapping.entrySet()) {
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                if (value.containsKey("properties")) {
                    Map<String, Object> childFieldType = new LinkedHashMap<>();
                    ((Map<String, Map<String, Object>>)value.get("properties"))
                            .forEach((propKey, propValue) -> childFieldType.put(propKey, propValue.get("type")));

                    fieldType.put(entry.getKey(), childFieldType);
                } else {
                    fieldType.put(entry.getKey(), value.get("type"));
                }
            }
            esFieldTypes.put(key, fieldType);

        }
        return fieldType;
    }

    private void putRelationDataFromRS(ESMapping mapping, SchemaItem schemaItem, ResultSet resultSet,
                                       Map<String, Object> esFieldData) {
        // 添加父子文档关联信息
        if (!mapping.getRelations().isEmpty()) {
            mapping.getRelations().forEach((relationField, relationMapping) -> {
                Map<String, Object> relations = new HashMap<>();
                relations.put("name", relationMapping.getName());
                if (StringUtils.isNotEmpty(relationMapping.getParent())) {
                    FieldItem parentFieldItem = schemaItem.getSelectFields().get(relationMapping.getParent());
                    Object parentVal;
                    try {
                        parentVal = getValFromRS(mapping,
                                resultSet,
                                parentFieldItem.getFieldName(),
                                parentFieldItem.getFieldName());
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    if (parentVal != null) {
                        relations.put("parent", parentVal.toString());
                        esFieldData.put("$parent_routing", parentVal.toString());

                    }
                }
                esFieldData.put(relationField, relations);
            });
        }
    }

    private void putRelationData(ESMapping mapping, SchemaItem schemaItem, Map<String, Object> dmlData,
                                 Map<String, Object> esFieldData) {
        // 添加父子文档关联信息
        if (!mapping.getRelations().isEmpty()) {
            mapping.getRelations().forEach((relationField, relationMapping) -> {
                Map<String, Object> relations = new HashMap<>();
                relations.put("name", relationMapping.getName());
                if (StringUtils.isNotEmpty(relationMapping.getParent())) {
                    FieldItem parentFieldItem = schemaItem.getSelectFields().get(relationMapping.getParent());
                    String columnName = parentFieldItem.getColumnItems().iterator().next().getColumnName();
                    Object parentVal = getValFromData(mapping, dmlData, parentFieldItem.getFieldName(), columnName);
                    if (parentVal != null) {
                        relations.put("parent", parentVal.toString());
                        esFieldData.put("$parent_routing", parentVal.toString());

                    }
                }
                esFieldData.put(relationField, relations);
            });
        }
    }
}
