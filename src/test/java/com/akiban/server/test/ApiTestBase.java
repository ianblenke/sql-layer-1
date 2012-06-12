/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.test;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import com.akiban.ais.model.AISBuilder;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.Group;
import com.akiban.ais.model.GroupIndex;
import com.akiban.ais.model.TableIndex;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.operator.SimpleQueryContext;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.rowtype.Schema;
import com.akiban.server.AkServerInterface;
import com.akiban.server.api.dml.scan.ScanFlag;
import com.akiban.server.rowdata.SchemaFactory;
import com.akiban.server.service.ServiceManagerImpl;
import com.akiban.server.service.config.ConfigurationService;
import com.akiban.server.rowdata.RowData;
import com.akiban.server.rowdata.RowDefCache;
import com.akiban.server.service.config.TestConfigService;
import com.akiban.server.service.dxl.DXLService;
import com.akiban.server.service.dxl.DXLTestHookRegistry;
import com.akiban.server.service.dxl.DXLTestHooks;
import com.akiban.server.service.servicemanager.GuicedServiceManager;
import com.akiban.server.service.tree.TreeService;
import com.akiban.server.types.extract.ConverterTestUtils;
import com.akiban.server.util.GroupIndexCreator;
import com.akiban.util.AssertUtils;
import com.akiban.util.Strings;
import com.akiban.util.tap.TapReport;
import com.akiban.util.Undef;
import com.persistit.Transaction;
import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;

import com.akiban.ais.model.GroupTable;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.server.api.dml.scan.RowDataOutput;
import com.akiban.server.service.config.Property;
import com.akiban.server.store.PersistitStore;
import com.akiban.server.store.Store;
import com.akiban.util.ListUtils;

import com.akiban.ais.model.Table;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.server.TableStatistics;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.api.DMLFunctions;
import com.akiban.server.api.dml.scan.CursorId;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.api.dml.scan.NiceRow;
import com.akiban.server.api.dml.scan.RowOutput;
import com.akiban.server.api.dml.scan.ScanAllRequest;
import com.akiban.server.api.dml.scan.ScanRequest;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.error.NoSuchTableException;
import com.akiban.server.service.ServiceManager;
import com.akiban.server.service.session.Session;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 * <p>Base class for all API tests. Contains a @SetUp that gives you a fresh DDLFunctions and DMLFunctions, plus
 * various convenience testing methods.</p>
 */
public class ApiTestBase {
    private static final String TAPS = System.getProperty("it.taps");
    protected final static Object UNDEF = Undef.only();
    private static final Comparator<? super TapReport> TAP_REPORT_COMPARATOR = new Comparator<TapReport>() {
        @Override
        public int compare(TapReport o1, TapReport o2) {
            return o1.getName().compareTo(o2.getName());
        }
    };

    public static class ListRowOutput implements RowOutput {
        private final List<NewRow> rows = new ArrayList<NewRow>();
        private final List<NewRow> rowsUnmodifiable = Collections.unmodifiableList(rows);
        private int mark = 0;

        @Override
        public void output(NewRow row) {
            rows.add(row);
        }

        public List<NewRow> getRows() {
            return rowsUnmodifiable;
        }

        public void clear() {
            rows.clear();
        }

        @Override
        public void mark() {
            mark = rows.size();
        }

        @Override
        public void rewind() {
            ListUtils.truncate(rows, mark);
        }
    }

    protected ApiTestBase(String suffix)
    {
        final String name = this.getClass().getSimpleName();
        if (!name.endsWith(suffix)) {
            throw new RuntimeException(
                    String.format("You must rename %s to something like Foo%s", name, suffix)
            );
        }
    }

    private ServiceManager sm;
    private Session session;
    private int aisGeneration;
    private int akibanFKCount;
    private boolean testServicesStarted;
    private final Set<RowUpdater> unfinishedRowUpdaters = new HashSet<RowUpdater>();
    
    @Rule
    public static final TestName testName = new TestName();
    
    protected String testName() {
        return testName.getMethodName();
    }

    @Before
    public final void startTestServices() throws Exception {
        assertTrue("some row updaters were left over: " + unfinishedRowUpdaters, unfinishedRowUpdaters.isEmpty());
        try {
            ConverterTestUtils.setGlobalTimezone("UTC");
            testServicesStarted = false;
            sm = createServiceManager( startupConfigProperties() );
            sm.startServices();
            ServiceManagerImpl.setServiceManager(sm);
            session = sm.getSessionService().createSession();
            testServicesStarted = true;
            if (TAPS != null) {
                sm.getStatisticsService().reset(TAPS);
                sm.getStatisticsService().setEnabled(TAPS, true);
            }
        } catch (Exception e) {
            handleStartupFailure(e);
        }
    }

    /**
     * Handle a failure during services startup. The default implementation is to just throw the exception, and
     * most tests should <em>not</em> override this. It's designed solely as a testing hook for FailureOnStartupIT.
     * @param e the startup exception
     * @throws Exception the startup exception
     */
    void handleStartupFailure(Exception e) throws Exception {
        throw e;
    }

    protected ServiceManager createServiceManager(Collection<Property> startupConfigProperties) {
        TestConfigService.setOverrides(startupConfigProperties);
        return new GuicedServiceManager(serviceBindingsProvider());
    }

    protected GuicedServiceManager.BindingsConfigurationProvider serviceBindingsProvider() {
        return GuicedServiceManager.testUrls();
    }

    @After
    public final void stopTestServices() throws Exception {
        Set<RowUpdater> localUnfinishedUpdaters = new HashSet<RowUpdater>(unfinishedRowUpdaters);
        unfinishedRowUpdaters.clear();
        ServiceManagerImpl.setServiceManager(null);
        if (!testServicesStarted) {
            return;
        }
        if (TAPS != null) {
            TapReport[] reports = sm.getStatisticsService().getReport(TAPS);
            Arrays.sort(reports, TAP_REPORT_COMPARATOR);
            for (TapReport report : reports) {
                long totalNanos = report.getCumulativeTime();
                double totalSecs = ((double) totalNanos) / 1000000000.0d;
                double secsPer = totalSecs / report.getOutCount();
                System.err.printf("%s:\t in=%d out=%d time=%.2f sec (%d nanos, %.5f sec per out)%n",
                        report.getName(),
                        report.getInCount(),
                        report.getOutCount(),
                        totalSecs,
                        totalNanos,
                        secsPer
                );
            }
        }
        String openCursorsMessage = null;
        if (sm.serviceIsStarted(DXLService.class)) {
            DXLTestHooks dxlTestHooks = DXLTestHookRegistry.get();
            // Check for any residual open cursors
            if (dxlTestHooks.openCursorsExist()) {
                openCursorsMessage = "open cursors remaining:" + dxlTestHooks.describeOpenCursors();
            }
        }

        if(session != null) {
            session.close();
        }
        sm.stopServices();
        sm = null;
        session = null;
        if (openCursorsMessage != null) {
            fail(openCursorsMessage);
        }
        testServicesStarted = false;
        assertTrue("not all updaters were used: " + localUnfinishedUpdaters, localUnfinishedUpdaters.isEmpty());
    }
    
    public final void crashTestServices() throws Exception {
        sm.crashServices();
        sm = null;
        session = null;
    }
    
    public final void restartTestServices(Collection<Property> properties) throws Exception {
        sm = createServiceManager( properties );
        sm.startServices();
        session = sm.getSessionService().createSession();
        ddl(); // loads up the schema manager et al
    }

    public final Session createNewSession()
    {
        return sm.getSessionService().createSession();
    }

    public final void safeRestartTestServices() throws Exception {
        final String datapath = serviceManager().getTreeService().getDataPath();
        Thread.sleep(1000);  // Let journal flush
        crashTestServices(); // TODO: WHY doesn't this work with stop?
        restartTestServices(Collections.singleton(new Property("akserver.datapath", datapath)));
    }
    
    protected final DMLFunctions dml() {
        return dxl().dmlFunctions();
    }

    protected final DDLFunctions ddl() {
        return dxl().ddlFunctions();
    }

    protected final Store store() {
        return sm.getStore();
    }

    protected final PersistitStore persistitStore() {
        return store().getPersistitStore();
    }
    
    protected final AkServerInterface akServer() {
        return sm.getAkSserver();
    }

    protected String akibanFK(String childCol, String parentTable, String parentCol) {
        ++akibanFKCount;
        return String.format("GROUPING FOREIGN KEY (%s) REFERENCES \"%s\" (%s)",
                             childCol, parentTable, parentCol
        );
    }

    protected final Session session() {
        return session;
    }

    protected final PersistitAdapter persistitAdapter(Schema schema) {
        return new PersistitAdapter(schema, persistitStore(), treeService(), session(), configService());
    }

    protected final QueryContext queryContext(PersistitAdapter adapter) {
        return new SimpleQueryContext(adapter);
    }

    protected final RowDefCache rowDefCache() {
        Store store = sm.getStore();
        return store.getRowDefCache();
    }

    protected final ServiceManager serviceManager() {
        return sm;
    }

    protected final ConfigurationService configService() {
        return sm.getConfigurationService();
    }

    protected final DXLService dxl() {
        return sm.getDXL();
    }

    protected final TreeService treeService() {
        return sm.getTreeService();
    }

    protected final int aisGeneration() {
        return aisGeneration;
    }

    protected final void updateAISGeneration() {
        aisGeneration = ddl().getGeneration();
    }

    protected Collection<Property> startupConfigProperties() {
        return Collections.emptyList();
    }

    protected AkibanInformationSchema createFromDDL(String schema, String ddl) {
        SchemaFactory schemaFactory = new SchemaFactory(schema);
        return schemaFactory.ais(ddl().getAIS(session()), ddl);
    }

    protected static final class SimpleColumn {
        public final String columnName;
        public final String typeName;
        public final Long param1;
        public final Long param2;

        public SimpleColumn(String columnName, String typeName) {
            this(columnName, typeName, null, null);
        }

        public SimpleColumn(String columnName, String typeName, Long param1, Long param2) {
            this.columnName = columnName;
            this.typeName = typeName;
            this.param1 = param1;
            this.param2 = param2;
        }
    }

    protected final int createTableFromTypes(String schema, String table, boolean firstIsPk, boolean createIndexes,
                                             SimpleColumn... columns) {
        AISBuilder builder = new AISBuilder();
        builder.userTable(schema, table);

        int colPos = 0;
        SimpleColumn pk = firstIsPk ? columns[0] : new SimpleColumn("id", "int");
        builder.column(schema, table, pk.columnName, colPos++, pk.typeName, null, null, false, false, null, null);
        builder.index(schema, table, Index.PRIMARY_KEY_CONSTRAINT, true, Index.PRIMARY_KEY_CONSTRAINT);
        builder.indexColumn(schema, table, Index.PRIMARY_KEY_CONSTRAINT, pk.columnName, 0, true, null);

        for(int i = firstIsPk ? 1 : 0; i < columns.length; ++i) {
            SimpleColumn sc = columns[i];
            String name = sc.columnName == null ? "c" + (colPos + 1) : sc.columnName;
            builder.column(schema, table, name, colPos++, sc.typeName, sc.param1, sc.param2, true, false, null, null);

            if(createIndexes) {
                builder.index(schema, table, name, false, Index.KEY_CONSTRAINT);
                builder.indexColumn(schema, table, name, name, 0, true, null);
            }
        }

        UserTable tempTable = builder.akibanInformationSchema().getUserTable(schema, table);
        ddl().createTable(session(), tempTable);
        updateAISGeneration();
        return tableId(schema, table);
    }
    
    protected final int createTableFromTypes(String schema, String table, boolean firstIsPk, boolean createIndexes,
                                             String... typeNames) {
        SimpleColumn simpleColumns[] = new SimpleColumn[typeNames.length];
        for(int i = 0; i < typeNames.length; ++i) {
            simpleColumns[i] = new SimpleColumn(null, typeNames[i]);
        }
        return createTableFromTypes(schema, table, firstIsPk, createIndexes, simpleColumns);
    }

    protected final int createTable(String schema, String table, String definition) throws InvalidOperationException {
        String ddl = String.format("CREATE TABLE \"%s\" (%s)", table, definition);
        AkibanInformationSchema tempAIS = createFromDDL(schema, ddl);
        UserTable tempTable = tempAIS.getUserTable(schema, table);
        ddl().createTable(session(), tempTable);
        updateAISGeneration();
        return ddl().getTableId(session(), new TableName(schema, table));
    }

    protected final int createTable(String schema, String table, String... definitions) throws InvalidOperationException {
        assertTrue("must have at least one definition element", definitions.length >= 1);
        StringBuilder unifiedDef = new StringBuilder();
        for (String definition : definitions) {
            unifiedDef.append(definition).append(',');
        }
        unifiedDef.setLength(unifiedDef.length() - 1);
        return createTable(schema, table, unifiedDef.toString());
    }

    protected final int createTable(TableName tableName, String... definitions) throws InvalidOperationException {
        return createTable(tableName.getSchemaName(), tableName.getTableName(), definitions);
    }

    private AkibanInformationSchema createIndexInternal(String schema, String table, String indexName, String... indexCols) {
        String ddl = String.format("CREATE INDEX \"%s\" ON \"%s\".\"%s\"(%s)", indexName, schema, table,
                                   Strings.join(Arrays.asList(indexCols), ","));
        return createFromDDL(schema, ddl);
    }

    protected final TableIndex createIndex(String schema, String table, String indexName, String... indexCols) {
        AkibanInformationSchema tempAIS = createIndexInternal(schema, table, indexName, indexCols);
        Index tempIndex = tempAIS.getUserTable(schema, table).getIndex(indexName);
        ddl().createIndexes(session(), Collections.singleton(tempIndex));
        updateAISGeneration();
        return ddl().getTable(session(), new TableName(schema, table)).getIndex(indexName);
    }

    /**
     * Add an Index to the given table that is marked as FOREIGN KEY. Intended
     * to be used by tests that need to simulate a table as created by the
     * adapter.
     */
    protected final TableIndex createGroupingFKIndex(String schema, String table, String indexName, String... indexCols) {
        assertTrue("grouping fk index must start with __akiban", indexName.startsWith("__akiban"));
        AkibanInformationSchema tempAIS = createIndexInternal(schema, table, indexName, indexCols);
        UserTable userTable = tempAIS.getUserTable(schema, table);
        TableIndex tempIndex = userTable.getIndex(indexName);
        userTable.removeIndexes(Collections.singleton(tempIndex));
        TableIndex fkIndex = TableIndex.create(tempAIS, userTable, indexName, 0, false, "FOREIGN KEY");
        for(IndexColumn col : tempIndex.getKeyColumns()) {
            fkIndex.addColumn(col);
        }
        ddl().createIndexes(session(), Collections.singleton(fkIndex));
        updateAISGeneration();
        return ddl().getTable(session(), new TableName(schema, table)).getIndex(indexName);
    }

    protected final TableIndex createTableIndex(int tableId, String indexName, boolean unique, String... columns) {
        return createTableIndex(getUserTable(tableId), indexName, unique, columns);
    }
    
    protected final TableIndex createTableIndex(UserTable table, String indexName, boolean unique, String... columns) {
        TableIndex index = new TableIndex(table, indexName, -1, unique, "KEY");
        int pos = 0;
        for (String columnName : columns) {
            Column column = table.getColumn(columnName);
            IndexColumn indexColumn = new IndexColumn(index, column, pos++, true, null);
            index.addColumn(indexColumn);
        }
        ddl().createIndexes(session(), Collections.singleton(index));
        return getUserTable(table.getTableId()).getIndex(indexName);
    }

    protected final GroupIndex createGroupIndex(String groupName, String indexName, String tableColumnPairs)
            throws InvalidOperationException {
        return createGroupIndex(groupName, indexName, tableColumnPairs, Index.JoinType.LEFT);
    }

    protected final GroupIndex createGroupIndex(String groupName, String indexName, String tableColumnPairs, Index.JoinType joinType)
            throws InvalidOperationException {
        AkibanInformationSchema ais = ddl().getAIS(session());
        final Index index;
        index = GroupIndexCreator.createIndex(ais, groupName, indexName, tableColumnPairs, joinType);
        ddl().createIndexes(session(), Collections.singleton(index));
        return ddl().getAIS(session()).getGroup(groupName).getIndex(indexName);
    }

    protected int createTablesAndIndexesFromDDL(String schema, String ddl) {
        SchemaFactory schemaFactory = new SchemaFactory(schema);
        AkibanInformationSchema ais = schemaFactory.ais(ddl);
        List<UserTable> tables = new ArrayList<UserTable>(ais.getUserTables().values());
        // Need to define from root the leaf; repeating definition order should work.
        Collections.sort(tables, new Comparator<UserTable>() {
                             @Override
                             public int compare(UserTable t1, UserTable t2) {
                                 return t1.getTableId().compareTo(t2.getTableId());
                             }
                         });
        for (UserTable table : tables) {
            ddl().createTable(session(), table);
        }
        for (Group group : ais.getGroups().values()) {
            Collection<GroupIndex> indexes = group.getIndexes();
            if (!indexes.isEmpty())
                ddl().createIndexes(session(), indexes);
        }
        updateAISGeneration();
        return ddl().getTableId(session(), tables.get(0).getName());
    }

    /**
     * Expects an exact number of rows. This checks both the countRowExactly and countRowsApproximately
     * methods on DMLFunctions.
     * @param tableId the table to count
     * @param rowsExpected how many rows we expect
     * @throws InvalidOperationException for various reasons :)
     */
    protected final void expectRowCount(int tableId, long rowsExpected) throws InvalidOperationException {
        TableStatistics tableStats = dml().getTableStatistics(session(), tableId, true);
        assertEquals("table ID", tableId, tableStats.getRowDefId());
        assertEquals("rows by TableStatistics", rowsExpected, tableStats.getRowCount());
    }

    protected static RuntimeException unexpectedException(Throwable cause) {
        return new RuntimeException("unexpected exception", cause);
    }

    protected final List<RowData> scanFull(ScanRequest request) {
        try {
            return RowDataOutput.scanFull(session(), aisGeneration(), dml(), request);
        } catch (InvalidOperationException e) {
            throw new TestException(e);
        }
    }

    protected final List<NewRow> scanAll(ScanRequest request) throws InvalidOperationException {
        ListRowOutput output = new ListRowOutput();
        CursorId cursorId = dml().openCursor(session(), aisGeneration(), request);

        dml().scanSome(session(), cursorId, output);
        dml().closeCursor(session(), cursorId);

        return output.getRows();
    }

    protected final List<NewRow> scanAllIndex(TableIndex index)  throws InvalidOperationException {
        final Set<Integer> columns = new HashSet<Integer>();
        for(IndexColumn icol : index.getKeyColumns()) {
            columns.add(icol.getColumn().getPosition());
        }
        return scanAll(new ScanAllRequest(index.getTable().getTableId(), columns, index.getIndexId(), null));
    }

    protected final void writeRow(int tableId, Object... values) {
        dml().writeRow(session(), createNewRow(tableId, values));
    }


    protected final RowUpdater update(NewRow oldRow) {
        RowUpdater updater = new RowUpdaterImpl(oldRow);
        unfinishedRowUpdaters.add(updater);
        return updater;
    }
    
    protected final RowUpdater update(int tableId, Object... values) {
        NewRow oldRow = createNewRow(tableId, values);
        return update(oldRow);
    }

    protected final int writeRows(NewRow... rows) throws InvalidOperationException {
        for (NewRow row : rows) {
            dml().writeRow(session(), row);
        }
        return rows.length;
    }

    protected final void deleteRow(int tableId, Object... values) {
        dml().deleteRow(session(), createNewRow(tableId, values));
    }

    protected final void expectRows(ScanRequest request, NewRow... expectedRows) throws InvalidOperationException {
        assertEquals("rows scanned", Arrays.asList(expectedRows), scanAll(request));
    }

    protected final ScanAllRequest scanAllRequest(int tableId) {
        Table uTable = ddl().getTable(session(), tableId);
        Set<Integer> allCols = new HashSet<Integer>();
        for (int i=0, MAX=uTable.getColumns().size(); i < MAX; ++i) {
            allCols.add(i);
        }
        return new ScanAllRequest(tableId, allCols);
    }

    protected final int indexId(String schema, String table, String index) {
        AkibanInformationSchema ais = ddl().getAIS(session());
        UserTable userTable = ais.getUserTable(schema, table);
        Index aisIndex = userTable.getIndex(index);
        if (aisIndex == null) {
            throw new RuntimeException("no such index: " + index);
        }
        return aisIndex.getIndexId();
    }

    protected final CursorId openFullScan(String schema, String table, String index) throws InvalidOperationException {
        AkibanInformationSchema ais = ddl().getAIS(session());
        UserTable userTable = ais.getUserTable(schema, table);
        Index aisIndex = userTable.getIndex(index);
        if (aisIndex == null) {
            throw new RuntimeException("no such index: " + index);
        }
        return openFullScan(
                userTable.getTableId(),
                aisIndex.getIndexId()
        );
    }

    protected final CursorId openFullScan(int tableId, int indexId) throws InvalidOperationException {
        Table uTable = ddl().getTable(session(), tableId);
        Set<Integer> allCols = new HashSet<Integer>();
        for (int i=0, MAX=uTable.getColumns().size(); i < MAX; ++i) {
            allCols.add(i);
        }
        ScanRequest request = new ScanAllRequest(tableId, allCols, indexId,
                EnumSet.of(ScanFlag.START_AT_BEGINNING, ScanFlag.END_AT_END)
        );
        return dml().openCursor(session(), aisGeneration(), request);
    }

    protected static <T> Set<T> set(T... items) {
        return new HashSet<T>(Arrays.asList(items));
    }

    protected static <T> T[] array(Class<T> ofClass, T... items) {
        if (ofClass == null) {
            throw new IllegalArgumentException(
                    "T[] of null class; you probably meant the array(Object...) overload "
                            +"with a null for the first element. Use array(Object.class, null, ...) instead"
            );
        }
        return items;
    }

    protected static Object[] array(Object... items) {
        return array(Object.class, items);
    }

    protected static <T> T get(NewRow row, int field, Class<T> castAs) {
        Object obj = row.get(field);
        return castAs.cast(obj);
    }

    protected final void expectFullRows(int tableId, NewRow... expectedRows) throws InvalidOperationException {
        ScanRequest all = scanAllRequest(tableId);
        expectRows(all, expectedRows);
        expectRowCount(tableId, expectedRows.length);
    }

    protected final List<NewRow> convertRowDatas(List<RowData> rowDatas) {
        List<NewRow> ret = new ArrayList<NewRow>(rowDatas.size());
        for(RowData rowData : rowDatas) {
            NewRow newRow = NiceRow.fromRowData(rowData, ddl().getRowDef(rowData.getRowDefId()));
            ret.add(newRow);
        }
        return ret;
    }

    protected static Set<CursorId> cursorSet(CursorId... cursorIds) {
        Set<CursorId> set = new HashSet<CursorId>();
        for (CursorId id : cursorIds) {
            if(!set.add(id)) {
                fail(String.format("while adding %s to %s", id, set));
            }
        }
        return set;
    }

    public NewRow createNewRow(int tableId, Object... columns) {
        return createNewRow(store(), tableId, columns);
    }

    public static NewRow createNewRow(Store store, int tableId, Object... columns) {
        NewRow row = new NiceRow(tableId, store);
        for (int i=0; i < columns.length; ++i) {
            if (columns[i] != UNDEF) {
                row.put(i, columns[i] );
            }
        }
        return row;
    }

    protected final void dropAllTables() throws InvalidOperationException {
        // Can't drop a parent before child. Get all to drop and sort children first (they always have higher id).
        List<Integer> allIds = new ArrayList<Integer>();
        for (Map.Entry<TableName, UserTable> entry : ddl().getAIS(session()).getUserTables().entrySet()) {
            if (!"akiban_information_schema".equals(entry.getKey().getSchemaName())) {
                allIds.add(entry.getValue().getTableId());
            }
        }
        Collections.sort(allIds, Collections.reverseOrder());
        for (Integer id : allIds) {
            ddl().dropTable(session(), tableName(id));
        }
        Set<TableName> uTables = new HashSet<TableName>(ddl().getAIS(session()).getUserTables().keySet());
        for (Iterator<TableName> iter = uTables.iterator(); iter.hasNext();) {
            if ("akiban_information_schema".equals(iter.next().getSchemaName())) {
                iter.remove();
            }
        }
        Assert.assertEquals("user tables", Collections.<TableName>emptySet(), uTables);
    }

    protected static <T> void assertEqualLists(String message, List<? extends T> expected, List<? extends T> actual) {
        AssertUtils.assertCollectionEquals(message, expected, actual);
    }

    protected static class TestException extends RuntimeException {
        private final InvalidOperationException cause;

        public TestException(String message, InvalidOperationException cause) {
            super(message, cause);
            this.cause = cause;
        }

        public TestException(InvalidOperationException cause) {
            super(cause);
            this.cause = cause;
        }

        @Override
        public InvalidOperationException getCause() {
            assert super.getCause() == cause;
            return cause;
        }
    }

    protected final int tableId(String schema, String table) {
        return tableId(new TableName(schema, table));
    }

    protected final int tableId(TableName tableName) {
        try {
            return ddl().getTableId(session(), tableName);
        } catch (NoSuchTableException e) {
            throw new TestException(e);
        }
    }

    protected final TableName tableName(int tableId) {
        try {
            return ddl().getTableName(session(), tableId);
        } catch (NoSuchTableException e) {
            throw new TestException(e);
        }
    }

    protected final TableName tableName(String schema, String table) {
        return new TableName(schema, table);
    }

    protected final UserTable getUserTable(String schema, String name) {
        return getUserTable(tableName(schema, name));
    }

    protected final UserTable getUserTable(TableName name) {
        return ddl().getUserTable(session(), name);
    }

    protected final UserTable getUserTable(int tableId) {
        final Table table;
        try {
            table = ddl().getTable(session(), tableId);
        } catch (NoSuchTableException e) {
            throw new TestException(e);
        }
        if (table.isUserTable()) {
            return (UserTable) table;
        }
        throw new RuntimeException("not a user table: " + table);
    }

    protected final Map<TableName,UserTable> getUserTables() {
        return stripAISTables(ddl().getAIS(session()).getUserTables());
    }

    protected final Map<TableName,GroupTable> getGroupTables() {
        return stripAISTables(ddl().getAIS(session()).getGroupTables());
    }

    private static <T extends Table> Map<TableName,T> stripAISTables(Map<TableName,T> map) {
        final Map<TableName,T> ret = new HashMap<TableName, T>(map);
        for(Iterator<TableName> iter=ret.keySet().iterator(); iter.hasNext(); ) {
            if("akiban_information_schema".equals(iter.next().getSchemaName())) {
                iter.remove();
            }
        }
        return ret;
    }

    protected void expectIndexes(int tableId, String... expectedIndexNames) {
        UserTable table = getUserTable(tableId);
        Set<String> expectedIndexesSet = new TreeSet<String>(Arrays.asList(expectedIndexNames));
        Set<String> actualIndexes = new TreeSet<String>();
        for (Index index : table.getIndexes()) {
            String indexName = index.getIndexName().getName();
            boolean added = actualIndexes.add(indexName);
            assertTrue("duplicate index name: " + indexName, added);
        }
        assertEquals("indexes in " + table.getName(), expectedIndexesSet, actualIndexes);
    }

    protected void expectIndexColumns(int tableId, String indexName, String... expectedColumns) {
        UserTable table = getUserTable(tableId);
        List<String> expectedColumnsList = Arrays.asList(expectedColumns);
        Index index = table.getIndex(indexName);
        assertNotNull(indexName + " was null", index);
        List<String> actualColumns = new ArrayList<String>();
        for (IndexColumn indexColumn : index.getKeyColumns()) {
            actualColumns.add(indexColumn.getColumn().getName());
        }
        assertEquals(indexName + " columns", actualColumns, expectedColumnsList);
    }

    public interface RowUpdater {
        void to(Object... values);
        void to(NewRow newRow);
    }

    private class RowUpdaterImpl implements RowUpdater {
        @Override
        public void to(Object... values) {
            NewRow newRow = createNewRow(oldRow.getTableId(), values);
            to(newRow);
        }

        @Override
        public void to(NewRow newRow) {
            boolean removed = unfinishedRowUpdaters.remove(this);
            dml().updateRow(session(), oldRow, newRow, null);
            assertTrue("couldn't remove row updater " + toString(), removed);
        }

        @Override
        public String toString() {
            return "RowUpdater for " + oldRow;
        }

        private RowUpdaterImpl(NewRow oldRow) {
            this.oldRow = oldRow;
        }

        private final NewRow oldRow;
    }

    protected <T> T transactionally(Callable<T> callable) throws Exception {
        Transaction txn = treeService().getTransaction(session());
        txn.begin();
        try {
            T value = callable.call();
            txn.commit();
            return value;
        }
        finally {
            txn.end();
        }
    }
    
    protected <T> T transactionallyUnchecked(Callable<T> callable) {
        try {
            return transactionally(callable);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    protected void transactionallyUnchecked(final Runnable runnable) {
        transactionallyUnchecked(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                runnable.run();
                return null;
            }
        });
    }
}
