package org.rakam.postgresql.report;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import io.airlift.log.Logger;
import org.postgresql.util.PGobject;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryResult;
import org.rakam.report.QueryStats;
import org.rakam.util.JsonHelper;
import org.rakam.util.LogUtil;
import org.skife.jdbi.v2.tweak.ConnectionFactory;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.rakam.collection.FieldType.STRING;
import static org.rakam.postgresql.analysis.PostgresqlEventStore.UTC_CALENDAR;
import static org.rakam.postgresql.report.PostgresqlQueryExecutor.QUERY_EXECUTOR;
import static org.rakam.report.QueryResult.EXECUTION_TIME;
import static org.rakam.report.QueryResult.QUERY;
import static org.rakam.report.QueryStats.State.FINISHED;
import static org.rakam.report.QueryStats.State.RUNNING;
import static org.rakam.util.JDBCUtil.fromSql;

public class PostgresqlQueryExecution
        implements QueryExecution
{
    private final static Logger LOGGER = Logger.get(PostgresqlQueryExecution.class);

    private final CompletableFuture<QueryResult> result;
    private final String query;
    private Statement statement;

    public PostgresqlQueryExecution(ConnectionFactory connectionPool, String query, boolean update)
    {
        this.query = query;

        // TODO: unnecessary threads will be spawn
        Supplier<QueryResult> task = () -> {
            final QueryResult queryResult;
            try (Connection connection = connectionPool.openConnection()) {
                statement = connection.createStatement();
                if (update) {
                    statement.executeUpdate(query);
                    // CREATE TABLE queries doesn't return any value and
                    // fail when using executeQuery so we fake the result data
                    queryResult = new QueryResult(ImmutableList.of(new SchemaField("result", FieldType.BOOLEAN)),
                            ImmutableList.of(ImmutableList.of(true)));
                }
                else {
                    long beforeExecuted = System.currentTimeMillis();
                    ResultSet resultSet = statement.executeQuery(query);
                    statement = null;
                    queryResult = resultSetToQueryResult(resultSet,
                            System.currentTimeMillis() - beforeExecuted);
                }
            }
            catch (Exception e) {
                QueryError error;
                if (e instanceof SQLException) {
                    SQLException cause = (SQLException) e;
                    error = new QueryError(cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), null, null);
                    LogUtil.logQueryError(query, error, PostgresqlQueryExecutor.class);
                }
                else {
                    LOGGER.error(e, "Internal query execution error");
                    error = new QueryError(e.getMessage(), null, null, null, null);
                }
                LOGGER.debug(e, format("Error while executing Postgresql query: \n%s", query));
                return QueryResult.errorResult(error, query);
            }

            return queryResult;
        };

        this.result = CompletableFuture.supplyAsync(task, QUERY_EXECUTOR);
    }

    @Override
    public QueryStats currentStats()
    {
        if (result.isDone()) {
            return new QueryStats(100, FINISHED, null, null, null, null, null, null);
        }
        else {
            return new QueryStats(null, RUNNING, null, null, null, null, null, null);
        }
    }

    @Override
    public boolean isFinished()
    {
        return result.isDone();
    }

    @Override
    public CompletableFuture<QueryResult> getResult()
    {
        return result;
    }

    @Override
    public void kill()
    {
        if (statement != null) {
            try {
                statement.cancel();
            }
            catch (SQLException e) {
                return;
            }
        }
    }

    private QueryResult resultSetToQueryResult(ResultSet resultSet, long executionTimeInMillis)
    {
        List<SchemaField> columns;
        List<List<Object>> data;
        try {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            columns = new ArrayList<>(columnCount);
            for (int i = 1; i < columnCount + 1; i++) {
                FieldType type;
                try {
                    type = fromSql(metaData.getColumnType(i), metaData.getColumnTypeName(i));
                }
                catch (UnsupportedOperationException e) {
                    LOGGER.warn(e.getMessage());
                    type = STRING;
                }

                columns.add(new SchemaField(metaData.getColumnName(i), type));
            }

            ImmutableList.Builder<List<Object>> builder = ImmutableList.builder();
            while (resultSet.next()) {
                List<Object> rowBuilder = Arrays.asList(new Object[columnCount]);
                for (int i = 0; i < columnCount; i++) {
                    Object object;
                    SchemaField schemaField = columns.get(i);
                    if (schemaField == null) {
                        continue;
                    }
                    FieldType type = schemaField.getType();
                    switch (type) {
                        case STRING:
                            object = resultSet.getString(i + 1);
                            break;
                        case LONG:
                            object = resultSet.getLong(i + 1);
                            break;
                        case INTEGER:
                            object = resultSet.getInt(i + 1);
                            break;
                        case DECIMAL:
                            BigDecimal bigDecimal = resultSet.getBigDecimal(i + 1);
                            object = bigDecimal != null ? bigDecimal.doubleValue() : null;
                            break;
                        case DOUBLE:
                            object = resultSet.getDouble(i + 1);
                            break;
                        case BOOLEAN:
                            object = resultSet.getBoolean(i + 1);
                            break;
                        case TIMESTAMP:
                            Timestamp timestamp = resultSet.getTimestamp(i + 1, UTC_CALENDAR);
                            object = timestamp != null ? timestamp.toInstant() : null;
                            break;
                        case DATE:
                            Date date = resultSet.getDate(i + 1, UTC_CALENDAR);
                            object = date != null ? date.toLocalDate() : null;
                            break;
                        case TIME:
                            Time time = resultSet.getTime(i + 1, UTC_CALENDAR);
                            object = time != null ? time.toLocalTime() : null;
                            break;
                        case BINARY:
                            InputStream binaryStream = resultSet.getBinaryStream(i + 1);
                            if (binaryStream != null) {
                                try {
                                    object = ByteStreams.toByteArray(binaryStream);
                                }
                                catch (IOException e) {
                                    LOGGER.error("Error while de-serializing BINARY type", e);
                                    object = null;
                                }
                            }
                            else {
                                object = null;
                            }
                            break;
                        default:
                            if (type.isArray()) {
                                Array array = resultSet.getArray(i + 1);
                                object = array == null ? null : array.getArray();
                            }
                            else if (type.isMap()) {
                                PGobject pgObject = (PGobject) resultSet.getObject(i + 1);
                                if (pgObject == null) {
                                    object = null;
                                }
                                else {
                                    if (pgObject.getType().equals("jsonb")) {
                                        object = JsonHelper.read(pgObject.getValue());
                                    }
                                    else {
                                        throw new UnsupportedOperationException("Postgresql type is not supported");
                                    }
                                }
                            }
                            else {
                                throw new IllegalStateException();
                            }
                    }

                    if (resultSet.wasNull()) {
                        object = null;
                    }

                    rowBuilder.set(i, object);
                }
                builder.add(rowBuilder);
            }
            data = builder.build();

            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i) == null) {
                    columns.set(i, new SchemaField(metaData.getColumnName(i + 1), STRING));
                }
            }
            return new QueryResult(columns, data, ImmutableMap.of(EXECUTION_TIME, executionTimeInMillis, QUERY, query));
        }
        catch (SQLException e) {
            QueryError error = new QueryError(e.getMessage(), e.getSQLState(), e.getErrorCode(), null, null);
            return QueryResult.errorResult(error, query);
        }
    }
}
