/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.types.common.types;

import com.foundationdb.server.error.UnknownDataTypeException;
import com.foundationdb.server.types.TClass;
import com.foundationdb.server.types.TInstance;
import com.foundationdb.server.types.aksql.aktypes.AkBool;
import com.foundationdb.server.types.aksql.aktypes.AkInterval;
import com.foundationdb.server.types.aksql.aktypes.AkResultSet;
import com.foundationdb.server.types.common.types.StringFactory.Charset;
import com.foundationdb.server.types.common.types.StringFactory;
import com.foundationdb.server.types.common.types.TString;

import com.foundationdb.sql.types.CharacterTypeAttributes;
import com.foundationdb.sql.types.DataTypeDescriptor;
import com.foundationdb.sql.types.TypeId;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;

/**
 * Translate between types in particular bundle(s) and Java / standard
 * SQL types.  SQL types are represented via Derby (sql-parser) types
 * or <code>java.sql.Types</code> members.
 */
public abstract class TypesTranslator
{
    
    /** Translate the given parser type to the corresponding type instance. */
    public TInstance toTInstance(DataTypeDescriptor sqlType) {
        TInstance tInstance;
        if (sqlType == null) 
            return null;
        else
            return toTInstance(sqlType.getTypeId(), sqlType);
    }

    protected TInstance toTInstance(TypeId typeId, DataTypeDescriptor sqlType) {
        switch (typeId.getTypeFormatId()) {
        /* No attribute types. */
        case TypeId.FormatIds.TINYINT_TYPE_ID:
            return jdbcInstance(Types.TINYINT, sqlType.isNullable());
        case TypeId.FormatIds.SMALLINT_TYPE_ID:
            return jdbcInstance(Types.SMALLINT, sqlType.isNullable());
        case TypeId.FormatIds.MEDIUMINT_ID:
        case TypeId.FormatIds.INT_TYPE_ID:
            return jdbcInstance(Types.INTEGER, sqlType.isNullable());
        case TypeId.FormatIds.LONGINT_TYPE_ID:
            return jdbcInstance(Types.BIGINT, sqlType.isNullable());
        case TypeId.FormatIds.DATE_TYPE_ID:
            return jdbcInstance(Types.DATE, sqlType.isNullable());
        case TypeId.FormatIds.TIME_TYPE_ID:
            return jdbcInstance(Types.TIME, sqlType.isNullable());
        case TypeId.FormatIds.TIMESTAMP_TYPE_ID:
            return jdbcInstance(Types.TIMESTAMP, sqlType.isNullable());
        case TypeId.FormatIds.REAL_TYPE_ID:
            return jdbcInstance(Types.REAL, sqlType.isNullable());
        case TypeId.FormatIds.DOUBLE_TYPE_ID:
            return jdbcInstance(Types.DOUBLE, sqlType.isNullable());
        case TypeId.FormatIds.BLOB_TYPE_ID:
            return jdbcInstance(Types.BLOB, sqlType.isNullable());
        /* Width attribute types. */
        case TypeId.FormatIds.BIT_TYPE_ID:
            return jdbcInstance(Types.BIT, sqlType.getMaximumWidth(), sqlType.isNullable());
        case TypeId.FormatIds.VARBIT_TYPE_ID:
            return jdbcInstance(Types.VARBINARY, sqlType.getMaximumWidth(), sqlType.isNullable());
        case TypeId.FormatIds.LONGVARBIT_TYPE_ID:
            return jdbcInstance(Types.LONGVARBINARY, sqlType.getMaximumWidth(), sqlType.isNullable());
        /* Precision, scale attribute types. */
        case TypeId.FormatIds.DECIMAL_TYPE_ID:
            return jdbcInstance(Types.DECIMAL, sqlType.getPrecision(), sqlType.getScale(), sqlType.isNullable());
        case TypeId.FormatIds.NUMERIC_TYPE_ID:
            return jdbcInstance(Types.NUMERIC, sqlType.getPrecision(), sqlType.getScale(), sqlType.isNullable());
        /* String (charset, collation) attribute types. */
        case TypeId.FormatIds.CHAR_TYPE_ID:
            return jdbcStringInstance(Types.CHAR, sqlType);
        case TypeId.FormatIds.VARCHAR_TYPE_ID:
            return jdbcStringInstance(Types.VARCHAR, sqlType);
        case TypeId.FormatIds.LONGVARCHAR_TYPE_ID:
            return jdbcStringInstance(Types.LONGVARCHAR, sqlType);
        case TypeId.FormatIds.CLOB_TYPE_ID:
            return jdbcStringInstance(Types.CLOB, sqlType);
        case TypeId.FormatIds.XML_TYPE_ID:
            return jdbcStringInstance(Types.SQLXML, sqlType);
        /* Special case AkSQL types. */
        case TypeId.FormatIds.BOOLEAN_TYPE_ID:
            return AkBool.INSTANCE.instance(sqlType.isNullable());
        case TypeId.FormatIds.INTERVAL_DAY_SECOND_ID:
            return AkInterval.SECONDS.tInstanceFrom(sqlType);
        case TypeId.FormatIds.INTERVAL_YEAR_MONTH_ID:
            return AkInterval.MONTHS.tInstanceFrom(sqlType);
        case TypeId.FormatIds.ROW_MULTISET_TYPE_ID_IMPL:
            {
                TypeId.RowMultiSetTypeId rmsTypeId = 
                    (TypeId.RowMultiSetTypeId)typeId;
                String[] columnNames = rmsTypeId.getColumnNames();
                DataTypeDescriptor[] columnTypes = rmsTypeId.getColumnTypes();
                List<AkResultSet.Column> columns = new ArrayList<>(columnNames.length);
                for (int i = 0; i < columnNames.length; i++) {
                    columns.add(new AkResultSet.Column(columnNames[i],
                                                       toTInstance(columnTypes[i])));
                }
                return AkResultSet.INSTANCE.instance(columns);
            }
        default:
            throw new UnknownDataTypeException(sqlType.toString());
        }
    }

    protected TInstance jdbcInstance(int jdbcType, boolean nullable) {
        TClass tclass = typeForJDBCType(jdbcType);
        if (tclass == null)
            return null;
        else
            return tclass.instance(nullable);
    }

    protected TInstance jdbcInstance(int jdbcType, int att, boolean nullable) {
        TClass tclass = typeForJDBCType(jdbcType);
        if (tclass == null)
            return null;
        else
            return tclass.instance(att, nullable);
    }

    protected TInstance jdbcInstance(int jdbcType, int att1, int att2, boolean nullable) {
        TClass tclass = typeForJDBCType(jdbcType);
        if (tclass == null)
            return null;
        else
            return tclass.instance(att1, att2, nullable);
    }

    protected TInstance jdbcStringInstance(int jdbcType, DataTypeDescriptor type) {
        TClass tclass = typeForJDBCType(jdbcType);
        if (tclass == null)
            return null;
        CharacterTypeAttributes typeAttributes = type.getCharacterAttributes();
        int charsetId = (typeAttributes == null)
                ? StringFactory.DEFAULT_CHARSET.ordinal()
                : Charset.of(typeAttributes.getCharacterSet()).ordinal();
        return tclass.instance(type.getMaximumWidth(), charsetId, type.isNullable());
    }
    
    public abstract TClass typeForJDBCType(int jdbcType);

    protected static String jdbcTypeName(int jdbcType) {
        try {
            for (Field field : Types.class.getFields()) {
                if (((field.getModifiers() & Modifier.STATIC) != 0) &&
                    Objects.equals(jdbcType, field.get(null))) {
                    return field.getName();
                }
            }
        }
        catch (Exception ex) {
        }
        return String.format("JDBC #%s", jdbcType);
    }
}