package com.alibaba.otter.canal.client.adapter.es;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.otter.canal.client.adapter.OuterAdapter;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfig;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfig.ESMapping;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfigLoader;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem;
import com.alibaba.otter.canal.client.adapter.es.config.SqlParser;
import com.alibaba.otter.canal.client.adapter.es.monitor.ESConfigMonitor;
import com.alibaba.otter.canal.client.adapter.es.service.ESEtlService;
import com.alibaba.otter.canal.client.adapter.es.service.ESSyncService;
import com.alibaba.otter.canal.client.adapter.es.support.ESConnection;
import com.alibaba.otter.canal.client.adapter.es.support.ESTemplate;
import com.alibaba.otter.canal.client.adapter.support.*;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.search.SearchResponse;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ES外部适配器
 *
 * @author rewerma 2018-10-20
 * @version 1.0.0
 */
@SPI(ESAdapter.ADAPTER_NAME)
public class ESAdapter implements OuterAdapter {

    /**
     * 适配器名称
     */
    public static final String ADAPTER_NAME = "es";

    private Map<String, ESSyncConfig>              esSyncConfig        = new ConcurrentHashMap<>(); // 文件名对应配置
    private Map<String, Map<String, ESSyncConfig>> dbTableEsSyncConfig = new ConcurrentHashMap<>(); // schema-table对应配置

    private ESConnection                           esConnection;

    private ESSyncService                          esSyncService;

    private ESConfigMonitor                        esConfigMonitor;

    private Properties                             envProperties;

    public ESSyncService getEsSyncService() {
        return esSyncService;
    }

    public Map<String, ESSyncConfig> getEsSyncConfig() {
        return esSyncConfig;
    }

    public Map<String, Map<String, ESSyncConfig>> getDbTableEsSyncConfig() {
        return dbTableEsSyncConfig;
    }

    public ESConnection getEsConnection() {
        return esConnection;
    }

    public void setEsConnection(ESConnection esConnection) {
        this.esConnection = esConnection;
    }

    @Override
    public void init(OuterAdapterConfig configuration, Properties envProperties) {
        try {
            this.envProperties = envProperties;
            Map<String, ESSyncConfig> esSyncConfigTmp = ESSyncConfigLoader.load(envProperties);
            // 过滤不匹配的key的配置
            esSyncConfigTmp.forEach((key, config) -> {
                if ((config.getOuterAdapterKey() == null && configuration.getKey() == null)
                    || (config.getOuterAdapterKey() != null
                        && config.getOuterAdapterKey().equalsIgnoreCase(configuration.getKey()))) {
                    esSyncConfig.put(key, config);
                }
            });

            //添加同步配置
            esSyncConfig.forEach(this::addDbTableEsSyncConfig);

            Map<String, String> properties = configuration.getProperties();

            String[] hostArray = configuration.getHosts().split(",");
            String mode = properties.get("mode");
            if ("rest".equalsIgnoreCase(mode) || "http".equalsIgnoreCase(mode)) {
                esConnection = new ESConnection(hostArray, properties, ESConnection.ESClientMode.REST);
            } else {
                esConnection = new ESConnection(hostArray, properties, ESConnection.ESClientMode.TRANSPORT);
            }

            ESTemplate esTemplate = new ESTemplate(esConnection);
            esSyncService = new ESSyncService(esTemplate);

            esConfigMonitor = new ESConfigMonitor();
            esConfigMonitor.init(this, envProperties);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 添加同步配置
     * @param configFileName es配置文件名
     * @param config 配置信息
     */
    public void addDbTableEsSyncConfig(String configFileName, ESSyncConfig config) {
        ESMapping esMapping = config.getEsMapping();

        List<SchemaItem> schemaItemList = new ArrayList<>();

        SchemaItem schemaItem = SqlParser.parse(esMapping.getSql());
        esMapping.setSchemaItem(schemaItem);
        schemaItemList.add(schemaItem);

        //对象字段列表
        Map<String, ESSyncConfig.ObjField> objFields = esMapping.getObjFields();
        if (MapUtils.isNotEmpty(objFields)) {
            objFields.values()
                    .stream()
                    .filter(objField -> StringUtils.isNotBlank(objField.getSql()))
                    .forEach(objField -> {
                        //解析子数据sql
                        SchemaItem childSchemaItem = SqlParser.parseChild(objField.getSql(), schemaItem);
                        objField.setSchemaItem(childSchemaItem);
                        objField.setSql(childSchemaItem.getSql());
                        schemaItemList.add(childSchemaItem);
                    });
        }

        DruidDataSource dataSource = DatasourceConfig.DATA_SOURCES.get(config.getDataSourceKey());
        if (dataSource == null || dataSource.getUrl() == null) {
            throw new RuntimeException("No data source found: " + config.getDataSourceKey());
        }
        Pattern pattern = Pattern.compile(".*:(.*)://.*/(.*)\\?.*$");
        Matcher matcher = pattern.matcher(dataSource.getUrl());
        if (!matcher.find()) {
            throw new RuntimeException("Not found the schema of jdbc-url: " + config.getDataSourceKey());
        }
        String schema = matcher.group(2);

        schemaItemList.stream()
                .map(SchemaItem::getAliasTableItems)
                .map(Map::values)
                .flatMap(Collection::stream)
                .forEach(tableItem -> {
                    String destination = config.getDestination();
                    String groupId = config.getGroupId();
                    String tableName = tableItem.getTableName();

                    String key = getDbTableEsSyncConfigKey(destination, groupId, schema, tableName);

                    dbTableEsSyncConfig
                            .computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                            .put(configFileName, config);
                });
    }

    /**
     * 移除同步配置
     * @param configFileName es配置文件名
     */
    public void delDbTableEsSyncConfig(String configFileName) {
        if (MapUtils.isEmpty(dbTableEsSyncConfig)) {
            return;
        }

        dbTableEsSyncConfig.values().forEach(configMap -> {
            if (configMap != null) {
                configMap.remove(configFileName);
            }
        });
    }

    @Override
    public void sync(List<Dml> dmls) {
        if (dmls == null || dmls.isEmpty()) {
            return;
        }
        for (Dml dml : dmls) {
            if (!dml.getIsDdl()) {
                sync(dml);
            }
        }
        esSyncService.commit(); // 批次统一提交

    }

    private void sync(Dml dml) {
        String database = dml.getDatabase();
        String table = dml.getTable();
        String destination = dml.getDestination();
        String groupId = dml.getGroupId();

        String key = getDbTableEsSyncConfigKey(destination, groupId, database, table);

        Map<String, ESSyncConfig> configMap = dbTableEsSyncConfig.get(key);

        if (configMap != null && !configMap.values().isEmpty()) {
            esSyncService.sync(configMap.values(), dml);
        }
    }

    @Override
    public EtlResult etl(String task, List<String> params) {
        EtlResult etlResult = new EtlResult();
        ESSyncConfig config = esSyncConfig.get(task);
        if (config != null) {
            DataSource dataSource = DatasourceConfig.DATA_SOURCES.get(config.getDataSourceKey());
            ESEtlService esEtlService = new ESEtlService(esConnection, config, esSyncService);
            if (dataSource != null) {
                return esEtlService.importData(params);
            } else {
                etlResult.setSucceeded(false);
                etlResult.setErrorMessage("DataSource not found");
                return etlResult;
            }
        } else {
            StringBuilder resultMsg = new StringBuilder();
            boolean resSuccess = true;
            for (ESSyncConfig configTmp : esSyncConfig.values()) {
                // 取所有的destination为task的配置
                if (configTmp.getDestination().equals(task)) {
                    ESEtlService esEtlService = new ESEtlService(esConnection, configTmp, esSyncService);
                    EtlResult etlRes = esEtlService.importData(params);
                    if (!etlRes.getSucceeded()) {
                        resSuccess = false;
                        resultMsg.append(etlRes.getErrorMessage()).append("\n");
                    } else {
                        resultMsg.append(etlRes.getResultMessage()).append("\n");
                    }
                }
            }
            if (resultMsg.length() > 0) {
                etlResult.setSucceeded(resSuccess);
                if (resSuccess) {
                    etlResult.setResultMessage(resultMsg.toString());
                } else {
                    etlResult.setErrorMessage(resultMsg.toString());
                }
                return etlResult;
            }
        }
        etlResult.setSucceeded(false);
        etlResult.setErrorMessage("Task not found");
        return etlResult;
    }

    @Override
    public Map<String, Object> count(String task) {
        ESSyncConfig config = esSyncConfig.get(task);
        ESMapping mapping = config.getEsMapping();
        SearchResponse response = this.esConnection.new ESSearchRequest(mapping.get_index(), mapping.get_type()).size(0)
            .getResponse();

        long rowCount = response.getHits().getTotalHits();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("esIndex", mapping.get_index());
        res.put("count", rowCount);
        return res;
    }

    @Override
    public void destroy() {
        if (esConfigMonitor != null) {
            esConfigMonitor.destroy();
        }
        if (esConnection != null) {
            esConnection.close();
        }
    }

    @Override
    public String getDestination(String task) {
        ESSyncConfig config = esSyncConfig.get(task);
        if (config != null) {
            return config.getDestination();
        }
        return null;
    }

    /**
     * 获取同步配置key
     * @param destination 对应canal的实例或者MQ的topic
     * @param groupId 对应mq的group id
     * @param database 数据库或schema
     * @param table 表名
     * @return
     */
    private String getDbTableEsSyncConfigKey(String destination, String groupId, String database, String table) {
        if (envProperties != null && !"tcp".equalsIgnoreCase(envProperties.getProperty("canal.conf.mode"))) {
            return StringUtils.trimToEmpty(destination) + "-" + StringUtils.trimToEmpty(groupId) + "_" + database + "-" + table;
        }
        return StringUtils.trimToEmpty(destination) + "_" + database + "-" + table;
    }
}
