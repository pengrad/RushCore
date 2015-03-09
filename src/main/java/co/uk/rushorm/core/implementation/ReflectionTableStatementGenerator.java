package co.uk.rushorm.core.implementation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import co.uk.rushorm.core.AnnotationCache;
import co.uk.rushorm.core.Rush;
import co.uk.rushorm.core.RushColumns;
import co.uk.rushorm.core.RushConfig;
import co.uk.rushorm.core.annotations.RushIgnore;
import co.uk.rushorm.core.annotations.RushList;
import co.uk.rushorm.core.exceptions.RushListAnnotationDoesNotMatchClassException;
import co.uk.rushorm.core.RushTableStatementGenerator;

/**
 * Created by stuartc on 11/12/14.
 */
public class ReflectionTableStatementGenerator implements RushTableStatementGenerator {

    private List<Join> joins = new ArrayList<>();

    private class Column {
        String name;
        String type;
    }

    private class Join {
        Class key;
        Field keyField;
        Class child;
    }

    private final RushConfig rushConfig;
    
    public ReflectionTableStatementGenerator(RushConfig rushConfig) {
        this.rushConfig = rushConfig;
    }
    
    @Override
    public void generateStatements(List<Class> classes, RushColumns rushColumns, StatementCallback statementCallback, Map<Class, AnnotationCache> annotationCache) {

        for(Class clazz : classes) {
            String sql = classToStatement(clazz, rushColumns, annotationCache);
            statementCallback.statementCreated(sql);
        }

        for(Join join : joins){
            String joinTableName = ReflectionUtils.joinTableNameForClass(join.key, join.child, join.keyField, annotationCache);
            String sql = joinToStatement(join, joinTableName, annotationCache);
            statementCallback.statementCreated(sql);
            if(!rushConfig.usingMySql()) {
                statementCallback.statementCreated(String.format(RushSqlUtils.CREATE_INDEX, joinTableName, joinTableName));
            }
        }
    }

    private String classToStatement(Class clazz, RushColumns rushColumns, Map<Class, AnnotationCache> annotationCache) {

        StringBuilder columnsStatement = new StringBuilder();

        List<Field> fields = new ArrayList<>();
        ReflectionUtils.getAllFields(fields, clazz);
        for (Field field : fields) {
            field.setAccessible(true);
            if(!field.isAnnotationPresent(RushIgnore.class)) {
                Column column = columnFromField(clazz, field, rushColumns);
                if(column != null) {
                    columnsStatement.append(",\n")
                            .append(column.name)
                            .append(" ")
                            .append(column.type);
                }
            }
        }
        return String.format(RushSqlUtils.TABLE_TEMPLATE, ReflectionUtils.tableNameForClass(clazz, annotationCache), columnsStatement.toString());
    }

    private String joinToStatement(Join join, String joinTableName, Map<Class, AnnotationCache> annotationCache) {
        return String.format(rushConfig.usingMySql() ? RushSqlUtils.JOIN_TEMPLATE_MYSQL : RushSqlUtils.JOIN_TEMPLATE_SQLITE, joinTableName,
                ReflectionUtils.tableNameForClass(join.key, annotationCache),
                ReflectionUtils.tableNameForClass(join.child, annotationCache));
    }

    private Column columnFromField(Class clazz, Field field, RushColumns rushColumns) {

        if(Rush.class.isAssignableFrom(field.getType())){

            // One to one join table
            Join join = new Join();
            join.key = clazz;
            join.keyField = field;
            join.child = field.getType();
            joins.add(join);
            return null;

        }else if(field.isAnnotationPresent(RushList.class)) {

            // One to many join table
            RushList rushList = field.getAnnotation(RushList.class);
            Class listClass;
            try {
                listClass = Class.forName(rushList.classname());
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                throw new RushListAnnotationDoesNotMatchClassException();
            }

            if (Rush.class.isAssignableFrom(listClass)) {

                Join join = new Join();
                join.key = clazz;
                join.keyField = field;
                join.child = listClass;
                joins.add(join);
                return null;
            }

        }
        if(rushColumns.supportsField(field)) {
            Column column = new Column();
            column.name = field.getName();
            column.type = rushColumns.sqlColumnType(field);
            return column;
        }

        return null;
    }
}