package com.alibaba.otter.canal.client.adapter.es.config;

import com.alibaba.fastsql.sql.SQLUtils;
import com.alibaba.fastsql.sql.ast.SQLExpr;
import com.alibaba.fastsql.sql.ast.expr.*;
import com.alibaba.fastsql.sql.ast.statement.*;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.fastsql.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.fastsql.sql.parser.ParserException;
import com.alibaba.fastsql.sql.parser.SQLStatementParser;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem.ColumnItem;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem.FieldItem;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem.RelationFieldsPair;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem.TableItem;
import com.alibaba.otter.canal.common.utils.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.Assert;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.alibaba.fastsql.sql.ast.expr.SQLBinaryOperator.BooleanAnd;
import static com.alibaba.fastsql.sql.ast.expr.SQLBinaryOperator.Equality;

/**
 * ES同步指定sql格式解析
 *
 * @author rewerma 2018-10-26 下午03:45:49
 * @version 1.0.0
 */
public class SqlParser {

    /**
     * 正则表达式 - 获取子数据查询语句中对主数据查询字段的引用表达式列表
     */
    public static final String REGEX_CHILD_SQL_MAIN_TABLE_FIELD_REF_EXPRS = "(\\s+)((\"|')?(\\$\\{(\\S*?)\\})(\"|')?)(\\s+)";

    /**
     * 正则表达式 - 获取子数据查询语句中和主数据查询字段的关联条件信息
     */
    public static final String REGEX_CHILD_SQL_MAIN_TABLE_RELATION_FIELDS = "(\\s+)(\\S*?)(\\s+)=" + REGEX_CHILD_SQL_MAIN_TABLE_FIELD_REF_EXPRS;

    /**
     * 子数据查询语句中的逻辑主数据“表”名
     */
    public static final String CHILD_SQL_MAIN_TABLE_LOGIC_NAME = "$_main";

    /**
     * 解析sql
     *
     * @param sql sql
     * @return 视图对象
     */
    public static SchemaItem parse(String sql) {
        try {
            SQLStatementParser parser = new MySqlStatementParser(sql);
            SQLSelectStatement statement = (SQLSelectStatement) parser.parseStatement();
            MySqlSelectQueryBlock sqlSelectQueryBlock = (MySqlSelectQueryBlock) statement.getSelect().getQuery();

            SchemaItem schemaItem = new SchemaItem();
            schemaItem.setMySqlSelectQueryBlock(sqlSelectQueryBlock);
            schemaItem.setSql(SQLUtils.toMySqlString(sqlSelectQueryBlock));
            SQLTableSource sqlTableSource = sqlSelectQueryBlock.getFrom();
            List<TableItem> tableItems = new ArrayList<>();
            SqlParser.visitSelectTable(schemaItem, sqlTableSource, tableItems, null);
            tableItems.forEach(tableItem -> schemaItem.getAliasTableItems().put(tableItem.getAlias(), tableItem));

            List<FieldItem> fieldItems = collectSelectQueryFields(sqlSelectQueryBlock);
            fieldItems.forEach(fieldItem -> schemaItem.getSelectFields().put(fieldItem.getFieldName(), fieldItem));

            schemaItem.init();

            if (schemaItem.getAliasTableItems().isEmpty() || schemaItem.getSelectFields().isEmpty()) {
                throw new ParserException("Parse sql error");
            }
            return schemaItem;
        } catch (Exception e) {
            throw new ParserException(e, sql);
        }
    }


    /**
     * 解析子数据sql
     *
     * @param childSql 子数据sql
     * @param mainSchemaItem 主数据sql视图对象
     * @return 视图对象
     */
    public static SchemaItem parseChild(String childSql, SchemaItem mainSchemaItem) {
        SchemaItem childSchemaItem = parse(childSql);
        childSql = childSchemaItem.getSql();
        Map<String, TableItem> aliasTableItems = childSchemaItem.getAliasTableItems();
        if (aliasTableItems.size()>1) {
            throw new UnsupportedOperationException("[Child sql] Only support one table!");
        }

        try {
            TableItem mainSqlItem = new TableItem(childSchemaItem);
            mainSqlItem.setAlias(CHILD_SQL_MAIN_TABLE_LOGIC_NAME);
            mainSqlItem.setTableName(CHILD_SQL_MAIN_TABLE_LOGIC_NAME);
            mainSqlItem.setSchema(mainSchemaItem.getMainTable().getSchema());

            //主数据sql查询字段列表
            Set<String> fieldNamesInMainSql = mainSchemaItem.getSelectFields().keySet();

            //子sql和主sql的关联关系字段信息
            List<RelationFieldsPair> relationFieldsPairs = new ArrayList<>();
            //子sql引用的主数据字段列表
            List<FieldItem> mainFieldRefList = new ArrayList<>();

            Matcher matcher = Pattern.compile(REGEX_CHILD_SQL_MAIN_TABLE_RELATION_FIELDS).matcher(childSql + " ");
            while (matcher.find()){
                FieldItem childFieldPart = new FieldItem();
                FieldItem mainFieldPart = new FieldItem();
                RelationFieldsPair relationFieldsPair = new RelationFieldsPair(childFieldPart, mainFieldPart);

                //子数据字段引用表达式，如：“r._id”
                String childFieldExpr = matcher.group(2);
                Assert.hasText(childFieldExpr, "[Child sql] child field expr parse error: " + matcher.group());
                childFieldPart.setExpr(childFieldExpr);

                //子数据字段名，如：“_id”
                String childFieldName = childFieldExpr.contains(".") ? StringUtils.substringAfter(childFieldExpr, ".") : childFieldExpr;
                Assert.hasText(childFieldName, "[Child sql] child field name parse error: " + matcher.group());
                childFieldPart.setFieldName(childFieldName);

                //对主数据查询字段的引用表达式，如：“${_id}”
                String mainFieldExpr = matcher.group(5);
                Assert.hasText(mainFieldExpr, "[Child sql] main field expr parse error: " + matcher.group());
                mainFieldPart.setExpr(mainFieldExpr);

                //主数据字段名，如：“_id”
                String mainFieldName = matcher.group(8);
                Assert.hasText(mainFieldName, "[Child sql] main field name parse error: " + matcher.group());
                mainFieldPart.setFieldName(mainFieldName);

                if (!CollectionUtils.exists(
                        fieldNamesInMainSql,
                        fieldName -> StringUtils.equalsIgnoreCase(mainFieldName, (String) fieldName))) {
                    throw new ParserException("Child sql field is not in the main sql fields: " + mainFieldName);
                }
                mainFieldRefList.add(mainFieldPart);
                relationFieldsPairs.add(relationFieldsPair);
            }
            if (CollectionUtils.isEmpty(relationFieldsPairs)) {
                throw new ParserException("Child sql hasn't associated with main sql field");
            }
            mainSqlItem.setSubQueryFields(mainFieldRefList);
            mainSqlItem.setRelationFields(relationFieldsPairs);

            aliasTableItems.put(CHILD_SQL_MAIN_TABLE_LOGIC_NAME, mainSqlItem);
            childSchemaItem.getTableItemAliases().put(CHILD_SQL_MAIN_TABLE_LOGIC_NAME, Collections.singletonList(mainSqlItem));

        } catch (Exception e) {
            throw new ParserException(e, childSql);
        }
        return childSchemaItem;
    }

    /**
     * 归集字段
     *
     * @param sqlSelectQueryBlock sqlSelectQueryBlock
     * @return 字段属性列表
     */
    private static List<FieldItem> collectSelectQueryFields(MySqlSelectQueryBlock sqlSelectQueryBlock) {
        return sqlSelectQueryBlock.getSelectList().stream().map(selectItem -> {
            FieldItem fieldItem = new FieldItem();
            fieldItem.setFieldName(selectItem.getAlias());
            fieldItem.setExpr(selectItem.toString());
            visitColumn(selectItem.getExpr(), fieldItem);
            return fieldItem;
        }).collect(Collectors.toList());
    }

    /**
     * 解析字段
     *
     * @param expr sql expr
     * @param fieldItem 字段属性
     */
    private static void visitColumn(SQLExpr expr, FieldItem fieldItem) {
        if (expr instanceof SQLIdentifierExpr) {
            // 无owner
            SQLIdentifierExpr identifierExpr = (SQLIdentifierExpr) expr;
            if (fieldItem.getFieldName() == null) {
                fieldItem.setFieldName(identifierExpr.getName());
                fieldItem.setExpr(identifierExpr.toString());
            }
            ColumnItem columnItem = new ColumnItem();
            columnItem.setColumnName(identifierExpr.getName());
            fieldItem.getOwners().add(null);
            fieldItem.addColumn(columnItem);
        } else if (expr instanceof SQLPropertyExpr) {
            // 有owner
            SQLPropertyExpr sqlPropertyExpr = (SQLPropertyExpr) expr;
            if (fieldItem.getFieldName() == null) {
                fieldItem.setFieldName(sqlPropertyExpr.getName());
                fieldItem.setExpr(sqlPropertyExpr.toString());
            }
            fieldItem.getOwners().add(sqlPropertyExpr.getOwnernName());
            ColumnItem columnItem = new ColumnItem();
            columnItem.setColumnName(sqlPropertyExpr.getName());
            columnItem.setOwner(sqlPropertyExpr.getOwnernName());
            fieldItem.addColumn(columnItem);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr methodInvokeExpr = (SQLMethodInvokeExpr) expr;
            fieldItem.setMethod(true);
            for (SQLExpr sqlExpr : methodInvokeExpr.getArguments()) {
                visitColumn(sqlExpr, fieldItem);
            }
        } else if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr sqlBinaryOpExpr = (SQLBinaryOpExpr) expr;
            fieldItem.setBinaryOp(true);
            visitColumn(sqlBinaryOpExpr.getLeft(), fieldItem);
            visitColumn(sqlBinaryOpExpr.getRight(), fieldItem);
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr sqlCaseExpr = (SQLCaseExpr) expr;
            fieldItem.setMethod(true);
            sqlCaseExpr.getItems().forEach(item-> visitColumn(item.getConditionExpr(), fieldItem));
        }else if(expr instanceof SQLCharExpr) {
            SQLCharExpr sqlCharExpr = (SQLCharExpr) expr;
            String owner = null;
            String columnName = null;
            if (sqlCharExpr.getParent() instanceof SQLCaseExpr.Item) {
                owner = ((SQLPropertyExpr) ((SQLCaseExpr.Item) sqlCharExpr.getParent()).getValueExpr()).getOwnernName();
                columnName = ((SQLPropertyExpr) ((SQLCaseExpr.Item) sqlCharExpr.getParent()).getValueExpr()).getName();
            }
            if (fieldItem.getFieldName() == null) {
                fieldItem.setFieldName(columnName);
                fieldItem.setExpr(sqlCharExpr.toString());
            }
            ColumnItem columnItem = new ColumnItem();
            columnItem.setColumnName(columnName);
            columnItem.setOwner(owner);
            fieldItem.getOwners().add(owner);
            fieldItem.addColumn(columnItem);
        }
    }

    /**
     * 解析表
     *
     * @param schemaItem 视图对象
     * @param sqlTableSource sqlTableSource
     * @param tableItems 表对象列表
     * @param tableItemTmp 表对象(临时)
     */
    private static void visitSelectTable(SchemaItem schemaItem, SQLTableSource sqlTableSource,
                                         List<TableItem> tableItems, TableItem tableItemTmp) {
        if (sqlTableSource instanceof SQLExprTableSource) {
            SQLExprTableSource sqlExprTableSource = (SQLExprTableSource) sqlTableSource;
            TableItem tableItem;
            if (tableItemTmp != null) {
                tableItem = tableItemTmp;
            } else {
                tableItem = new TableItem(schemaItem);
            }
            tableItem.setSchema(sqlExprTableSource.getSchema());
            tableItem.setTableName(sqlExprTableSource.getTableName());
            if (tableItem.getAlias() == null) {
                tableItem.setAlias(sqlExprTableSource.getAlias());
            }
            if (tableItems.isEmpty()) {
                // 第一张表为主表
                tableItem.setMain(true);
            }
            tableItems.add(tableItem);
        } else if (sqlTableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource sqlJoinTableSource = (SQLJoinTableSource) sqlTableSource;
            SQLTableSource leftTableSource = sqlJoinTableSource.getLeft();
            visitSelectTable(schemaItem, leftTableSource, tableItems, null);
            SQLTableSource rightTableSource = sqlJoinTableSource.getRight();
            TableItem rightTableItem = new TableItem(schemaItem);
            // 解析on条件字段
            visitOnCondition(sqlJoinTableSource.getCondition(), rightTableItem);
            visitSelectTable(schemaItem, rightTableSource, tableItems, rightTableItem);

        } else if (sqlTableSource instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subQueryTableSource = (SQLSubqueryTableSource) sqlTableSource;
            MySqlSelectQueryBlock sqlSelectQuery = (MySqlSelectQueryBlock) subQueryTableSource.getSelect().getQuery();
            TableItem tableItem;
            if (tableItemTmp != null) {
                tableItem = tableItemTmp;
            } else {
                tableItem = new TableItem(schemaItem);
            }
            tableItem.setAlias(subQueryTableSource.getAlias());
            tableItem.setSubQuerySql(SQLUtils.toMySqlString(sqlSelectQuery));
            tableItem.setSubQuery(true);
            tableItem.setSubQueryFields(collectSelectQueryFields(sqlSelectQuery));
            visitSelectTable(schemaItem, sqlSelectQuery.getFrom(), tableItems, tableItem);
        }
    }

    /**
     * 解析on条件
     *
     * @param expr sql expr
     * @param tableItem 表对象
     */
    private static void visitOnCondition(SQLExpr expr, TableItem tableItem) {
        if (!(expr instanceof SQLBinaryOpExpr)) {
            throw new UnsupportedOperationException();
        }
        SQLBinaryOpExpr sqlBinaryOpExpr = (SQLBinaryOpExpr) expr;
        if (sqlBinaryOpExpr.getOperator() == BooleanAnd) {
            visitOnCondition(sqlBinaryOpExpr.getLeft(), tableItem);
            visitOnCondition(sqlBinaryOpExpr.getRight(), tableItem);
        } else if (sqlBinaryOpExpr.getOperator() == Equality) {
            FieldItem leftFieldItem = new FieldItem();
            visitColumn(sqlBinaryOpExpr.getLeft(), leftFieldItem);
            if (leftFieldItem.getColumnItems().size() != 1 || leftFieldItem.isMethod() || leftFieldItem.isBinaryOp()) {
                throw new UnsupportedOperationException("Unsupported for complex of on-condition");
            }
            FieldItem rightFieldItem = new FieldItem();
            visitColumn(sqlBinaryOpExpr.getRight(), rightFieldItem);
            if (rightFieldItem.getColumnItems().size() != 1 || rightFieldItem.isMethod()
                || rightFieldItem.isBinaryOp()) {
                throw new UnsupportedOperationException("Unsupported for complex of on-condition");
            }
            tableItem.getRelationFields().add(new RelationFieldsPair(leftFieldItem, rightFieldItem));
        } else {
            throw new UnsupportedOperationException("Unsupported for complex of on-condition");
        }
    }
}
