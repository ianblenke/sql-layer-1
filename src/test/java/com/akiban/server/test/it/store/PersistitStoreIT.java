/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.test.it.store;

import static com.akiban.server.service.tree.TreeService.SCHEMA_TREE_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.List;

import com.akiban.server.RowDefCache;
import com.akiban.server.service.session.Session;
import com.akiban.server.service.tree.TreeServiceImpl;
import com.akiban.server.store.PersistitStore;
import com.akiban.server.store.RowCollector;
import com.akiban.server.store.Store;
import com.akiban.server.test.it.ITBase;
import com.persistit.exception.PersistitException;
import org.junit.Before;
import org.junit.Test;

import com.akiban.server.IndexDef;
import com.akiban.server.InvalidOperationException;
import com.akiban.server.RowData;
import com.akiban.server.RowDef;
import com.akiban.server.service.session.SessionImpl;
import com.akiban.server.service.tree.TreeLink;
import com.akiban.message.ErrorCode;
import com.akiban.util.ByteBufferFactory;
import com.persistit.Exchange;
import com.persistit.Tree;
import com.persistit.Volume;

public class PersistitStoreIT extends ITBase {

    private final static boolean BUILD_INDEXES_DEFERRED = true;

    private interface RowVisitor {
        void visit(final int depth) throws Exception;
    }

    private RowDef rowDef(final String name) {
        return store().getRowDefCache().getRowDef(DataDictionaryDDL.SCHEMA + "." + name);
    }

    class TestData {
        final RowDef defC = rowDef("customer");
        final RowDef defO = rowDef("order");
        final RowDef defI = rowDef("item");
        final RowDef defA = rowDef("address");
        final RowDef defX = rowDef("component");
        final RowDef defCOI = rowDef("_akiban_customer");
        final RowData rowC = new RowData(new byte[256]);
        final RowData rowO = new RowData(new byte[256]);
        final RowData rowI = new RowData(new byte[256]);
        final RowData rowA = new RowData(new byte[256]);
        final RowData rowX = new RowData(new byte[256]);
        final int customers;
        final int ordersPerCustomer;
        final int itemsPerOrder;
        final int componentsPerItem;

        long cid;
        long oid;
        long iid;
        long xid;

        long elapsed;
        long count = 0;

        TestData(final int customers, final int ordersPerCustomer,
                final int itemsPerOrder, final int componentsPerItem) {
            this.customers = customers;
            this.ordersPerCustomer = ordersPerCustomer;
            this.itemsPerOrder = itemsPerOrder;
            this.componentsPerItem = componentsPerItem;
        }

        void insertTestRows() throws Exception {
            Store store = store();
            Session session = session();
            elapsed = System.nanoTime();
            int unique = 0;
            for (int c = 0; ++c <= customers;) {
                cid = c;
                rowC.reset(0, 256);
                rowC.createRow(defC, new Object[] { cid, "Customer_" + cid });
                store.writeRow(session, rowC);
                for (int o = 0; ++o <= ordersPerCustomer;) {
                    oid = cid * 1000 + o;
                    rowO.reset(0, 256);
                    rowO.createRow(defO, new Object[] { oid, cid, 12345 });
                    store.writeRow(session, rowO);
                    for (int i = 0; ++i <= itemsPerOrder;) {
                        iid = oid * 1000 + i;
                        rowI.reset(0, 256);
                        rowI.createRow(defI, new Object[] { oid, iid, 123456,
                                654321 });
                        store.writeRow(session, rowI);
                        for (int x = 0; ++x <= componentsPerItem;) {
                            xid = iid * 1000 + x;
                            rowX.reset(0, 256);
                            rowX.createRow(defX, new Object[] { iid, xid, c,
                                    ++unique, "Description_" + unique });
                            store.writeRow(session, rowX);
                        }
                    }
                }
                for (int a = 0; a < (c % 3); a++) {
                    rowA.reset(0, 256);
                    rowA.createRow(defA, new Object[] { c, a, "addr1_" + c,
                            "addr2_" + c, "addr3_" + c });
                    store.writeRow(session, rowA);
                }
            }
            elapsed = System.nanoTime() - elapsed;
        }

        void visitTestRows(final RowVisitor visitor) throws Exception {
            elapsed = System.nanoTime();
            int unique = 0;
            for (int c = 0; ++c <= customers;) {
                cid = c;
                rowC.reset(0, 256);
                rowC.createRow(defC, new Object[] { cid, "Customer_" + cid });
                visitor.visit(0);
                for (int o = 0; ++o <= ordersPerCustomer;) {
                    oid = cid * 1000 + o;
                    rowO.reset(0, 256);
                    rowO.createRow(defO, new Object[] { oid, cid, 12345 });
                    visitor.visit(1);
                    for (int i = 0; ++i <= itemsPerOrder;) {
                        iid = oid * 1000 + i;
                        rowI.reset(0, 256);
                        rowI.createRow(defI, new Object[] { oid, iid, 123456,
                                654321 });
                        visitor.visit(2);
                        for (int x = 0; ++x <= componentsPerItem;) {
                            xid = iid * 1000 + x;
                            rowX.reset(0, 256);
                            rowX.createRow(defX, new Object[] { iid, xid, c,
                                    ++unique, "Description_" + unique });
                            visitor.visit(3);
                        }
                    }
                }
            }
            elapsed = System.nanoTime() - elapsed;

        }

        int totalRows() {
            return totalCustomerRows() + totalOrderRows() + totalItemRows()
                    + totalComponentRows();
        }

        int totalCustomerRows() {
            return customers;
        }

        int totalOrderRows() {
            return customers * ordersPerCustomer;
        }

        int totalItemRows() {
            return customers * ordersPerCustomer * itemsPerOrder;
        }

        int totalComponentRows() {
            return customers * ordersPerCustomer * itemsPerOrder
                    * componentsPerItem;
        }

        void start() {
            elapsed = System.nanoTime();
        }

        void end() {
            elapsed = System.nanoTime() - elapsed;
        }
    }

    @Before
    public void setUp() throws Exception {
        DataDictionaryDDL.createTables(session(), ddl());
    }

    @Test
    public void testWriteCOIrows() throws Exception {
        final TestData td = new TestData(10, 10, 10, 10);
        td.insertTestRows();
        System.out.println("testWriteCOIrows: inserted " + td.totalRows()
                + " rows in " + (td.elapsed / 1000L) + "us");

    }

    @Test
    public void testScanCOIrows() throws Exception {
        final TestData td = new TestData(1000, 10, 3, 2);
        td.insertTestRows();
        System.out.println("testScanCOIrows: inserted " + td.totalRows()
                + " rows in " + (td.elapsed / 1000L) + "us");
        {
            // simple test - get all I rows
            td.start();
            int scanCount = 0;
            td.rowI.createRow(td.defI, new Object[] { null, null, null });

            final byte[] columnBitMap = new byte[] { 0xF };
            final int indexId = 0;

            final RowCollector rc = store().newRowCollector(session(),
                                                            td.defI.getRowDefId(), indexId, 0, td.rowI, td.rowI,
                                                            columnBitMap);
            final ByteBuffer payload = ByteBufferFactory.allocate(256);

            while (rc.hasMore()) {
                payload.clear();
                while (rc.collectNextRow(payload))
                    ;
                payload.flip();
                RowData rowData = new RowData(payload.array(), payload.position(), payload.limit());
                for (int p = rowData.getBufferStart(); p < rowData.getBufferEnd();) {
                    rowData.prepareRow(p);
                    p = rowData.getRowEnd();
                    scanCount++;
                }
            }
            assertEquals(td.totalItemRows(), scanCount);
            td.end();
            System.out.println("testScanCOIrows: scanned " + scanCount
                    + " rows in " + (td.elapsed / 1000L) + "us");

        }

        {
            // select item by IID in user table `item`
            td.start();
            int scanCount = 0;
            td.rowI.createRow(td.defI,
                    new Object[] { null, Integer.valueOf(1001001), null, null });

            final byte[] columnBitMap = new byte[] { (byte) 0x3 };
            final int indexId = td.defI.getPKIndexDef().getId();

            final RowCollector rc = store().newRowCollector(session(),
                                                            td.defI.getRowDefId(), indexId, 0, td.rowI, td.rowI,
                                                            columnBitMap);
            final ByteBuffer payload = ByteBufferFactory.allocate(256);

            while (rc.hasMore()) {
                payload.clear();
                while (rc.collectNextRow(payload))
                    ;
                payload.flip();
                RowData rowData = new RowData(payload.array(),
                        payload.position(), payload.limit());
                for (int p = rowData.getBufferStart(); p < rowData
                        .getBufferEnd();) {
                    rowData.prepareRow(p);
                    p = rowData.getRowEnd();
                    scanCount++;
                }
            }
            assertEquals(1, scanCount);
            td.end();
            System.out.println("testScanCOIrows: scanned " + scanCount
                    + " rows in " + (td.elapsed / 1000L) + "us");

        }

        {
            // select items in COI table by index values on Order
            td.start();
            int scanCount = 0;
            final RowData start = new RowData(new byte[256]);
            final RowData end = new RowData(new byte[256]);
            // C has 2 columns, O has 3 columns, A has 5 columns, I has 4
            // columns, CC has 5 columns
            final Object[] values = new Object[td.defCOI.getFieldCount()];
            final int order_id_field = td.defCOI
                    .getFieldIndex("order$order_id");
            values[order_id_field] = 1004;
            start.createRow(td.defCOI, values);
            values[order_id_field] = 1007;
            end.createRow(td.defCOI, values);
            final byte[] columnBitMap = projection(new RowDef[] { td.defC,
                    td.defO, td.defI }, td.defCOI.getFieldCount());

            int indexId = findIndexId(td.defCOI, td.defO, 0);
            final RowCollector rc = store().newRowCollector(session(),
                                                            td.defCOI.getRowDefId(), indexId, 0, start, end,
                                                            columnBitMap);
            final ByteBuffer payload = ByteBufferFactory.allocate(256);
            //
            // Expect all the C, O and I rows for orders 1004 through 1007,
            // inclusive
            // Total of 40
            //
            while (rc.hasMore()) {
                payload.clear();
                while (rc.collectNextRow(payload))
                    ;
                payload.flip();
                RowData rowData = new RowData(payload.array(),
                        payload.position(), payload.limit());
                for (int p = rowData.getBufferStart(); p < rowData
                        .getBufferEnd();) {
                    rowData.prepareRow(p);
                    System.out.println(rowData.toString(store().getRowDefCache()));
                    p = rowData.getRowEnd();
                    scanCount++;
                }
            }
            assertEquals(rc.getDeliveredRows(), scanCount);
            assertEquals(17, scanCount - rc.getRepeatedRows());
            td.end();
            System.out.println("testScanCOIrows: scanned " + scanCount
                    + " rows in " + (td.elapsed / 1000L) + "us");
        }
    }

    int findIndexId(final RowDef groupRowDef, final RowDef userRowDef,
            final int fieldIndex) {
        int indexId = -1;
        final int findField = fieldIndex + userRowDef.getColumnOffset();
        for (final IndexDef indexDef : groupRowDef.getIndexDefs()) {
            if (indexDef.getFields().length == 1
                    && indexDef.getFields()[0] == findField) {
                indexId = indexDef.getId();
            }
        }
        return indexId;
    }

    final byte[] projection(final RowDef[] rowDefs, final int width) {
        final byte[] bitMap = new byte[(width + 7) / 8];
        for (final RowDef rowDef : rowDefs) {
            for (int bit = rowDef.getColumnOffset(); bit < rowDef
                    .getColumnOffset() + rowDef.getFieldCount(); bit++) {
                bitMap[bit / 8] |= (1 << (bit % 8));
            }
        }
        return bitMap;
    }

    @Test
    public void testBug686910() throws Exception {
        Store store = store();
        Session session = session();
        for (int loop = 0; loop < 5; loop++) {
            final TestData td = new TestData(5, 5, 5, 5);
            td.insertTestRows();
            store.truncateGroup(session, td.defI.getRowDefId());
            store.truncateGroup(session, td.defO.getRowDefId());
            store.truncateGroup(session, td.defC.getRowDefId());
            store.truncateGroup(session, td.defCOI.getRowDefId());
            store.truncateGroup(session, td.defA.getRowDefId());
            store.truncateGroup(session, td.defX.getRowDefId());

            assertTrue(isGone(td.defCOI));
            assertTrue(isGone(td.defO));
            assertTrue(isGone(td.defI));
        }
    }

    @Test
    public void testUniqueIndexes() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        td.rowX.createRow(td.defX, new Object[] { 1002003, 23890345, 123, 44,
                "test1" });
        ErrorCode actual = null;
        try {
            store().writeRow(session(), td.rowX);
        } catch (InvalidOperationException e) {
            actual = e.getCode();
        }
        assertEquals(ErrorCode.DUPLICATE_KEY, actual);
        td.rowX.createRow(td.defX, new Object[] { 1002003, 23890345, 123,
                44444, "test2" });
        store().writeRow(session(), td.rowX);
    }

    @Test
    public void testUpdateRows() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        long cid = 3;
        long oid = cid * 1000 + 2;
        long iid = oid * 1000 + 4;
        long xid = iid * 1000 + 3;
        td.rowX.createRow(td.defX, new Object[] { iid, xid, null, null });
        final byte[] columnBitMap = new byte[] { (byte) 0x1F };
        final ByteBuffer payload = ByteBufferFactory.allocate(1024);

        RowCollector rc;
        rc = store().newRowCollector(session(), td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, td.rowX, td.rowX, columnBitMap);
        payload.clear();
        assertTrue(rc.collectNextRow(payload));
        payload.flip();
        RowData oldRowData = new RowData(payload.array(), payload.position(),
                payload.limit());
        oldRowData.prepareRow(oldRowData.getBufferStart());

        RowData newRowData = new RowData(new byte[256]);
        newRowData.createRow(td.defX, new Object[] { iid, xid, 4, 424242,
                "Description_424242" });
        store().updateRow(session(), oldRowData, newRowData, null);

        rc = store().newRowCollector(session(), td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, td.rowX, td.rowX, columnBitMap);
        payload.clear();
        assertTrue(rc.collectNextRow(payload));
        payload.flip();

        RowData updateRowData = new RowData(payload.array(),
                payload.position(), payload.limit());
        updateRowData.prepareRow(updateRowData.getBufferStart());
        System.out.println(updateRowData.toString(store().getRowDefCache()));
        //
        // Now attempt to update a leaf table's PK field.
        //
        newRowData = new RowData(new byte[256]);
        newRowData.createRow(td.defX, new Object[] { iid, -xid, 4, 545454,
                "Description_545454" });

        store().updateRow(session(), updateRowData, newRowData, null);

        rc = store().newRowCollector(session(), td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, updateRowData, updateRowData,
                                     columnBitMap);
        payload.clear();
        assertTrue(!rc.collectNextRow(payload));

        rc = store().newRowCollector(session(), td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, newRowData, newRowData,
                                     columnBitMap);

        assertTrue(rc.collectNextRow(payload));
        payload.flip();

        updateRowData = new RowData(payload.array(), payload.position(),
                payload.limit());
        updateRowData.prepareRow(updateRowData.getBufferStart());
        System.out.println(updateRowData.toString(store().getRowDefCache()));

        // TODO:
        // Hand-checked the index tables. Need SELECT on secondary indexes to
        // verify them automatically.
    }

    @Test
    public void testDeleteRows() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        td.count = 0;
        final RowVisitor visitor = new RowVisitor() {
            public void visit(final int depth) throws Exception {
                ErrorCode expectedError = null;
                ErrorCode actualError = null;
                try {
                    switch (depth) {
                        case 0:
                        case 1:
                        case 2:
                            break;
/*
                    case 0:
                        // TODO - for now we can't do cascading DELETE so we
                        // expect an error
                        expectedError = ErrorCode.FK_CONSTRAINT_VIOLATION;
                        store.deleteRow(session, td.rowC);
                        break;
                    case 1:
                        // TODO - for now we can't do cascading DELETE so we
                        // expect an error
                        expectedError = ErrorCode.FK_CONSTRAINT_VIOLATION;
                        store.deleteRow(session, td.rowO);
                        break;
                    case 2:
                        // TODO - for now we can't do cascading DELETE so we
                        // expect an error
                        expectedError = ErrorCode.FK_CONSTRAINT_VIOLATION;
                        store.deleteRow(session, td.rowI);
                        break;
*/
                    case 3:
                        expectedError = null;
                        if (td.xid % 2 == 0) {
                            store().deleteRow(session(), td.rowX);
                            td.count++;
                        }
                        break;
                    default:
                        throw new Exception("depth = " + depth);
                    }
                } catch (InvalidOperationException e) {
                    actualError = e.getCode();
                }
                assertEquals("at depth " + depth, expectedError, actualError);
            }
        };
        td.visitTestRows(visitor);

        int scanCount = 0;
        td.rowX.createRow(td.defX, new Object[0]);
        final byte[] columnBitMap = new byte[] { (byte) 0x1F };
        final RowCollector rc = store().newRowCollector(session(),
                                                        td.defX.getRowDefId(), td.defX.getPKIndexDef().getId(), 0,
                                                        td.rowX, td.rowX, columnBitMap);
        final ByteBuffer payload = ByteBufferFactory.allocate(256);

        while (rc.hasMore()) {
            payload.clear();
            while (rc.collectNextRow(payload))
                ;
            payload.flip();
            RowData rowData = new RowData(payload.array(), payload.position(),
                    payload.limit());
            for (int p = rowData.getBufferStart(); p < rowData.getBufferEnd();) {
                rowData.prepareRow(p);
                p = rowData.getRowEnd();
                scanCount++;
            }
        }
        assertEquals(td.totalComponentRows() - td.count, scanCount);
        // TODO:
        // Hand-checked the index tables. Need SELECT on secondary indexes to
        // verify them automatically.
    }

    private void dump(String label, List<RowData> rows) {
        System.out.println(label + ":");
        for (RowData row : rows) {
            System.out.println(row.toString(store().getRowDefCache()));
        }
    }


    @Test
    public void testDeferIndex() throws Exception {
        final TestData td = new TestData(3, 3, 0, 0);
        store().setDeferIndexes(true);
        td.insertTestRows();
        final StringWriter a, b, c, d;
        dumpIndexes(new PrintWriter(a = new StringWriter()));
        store().flushIndexes(session());
        dumpIndexes(new PrintWriter(b = new StringWriter()));
        store().deleteIndexes(new SessionImpl(), "");
        dumpIndexes(new PrintWriter(c = new StringWriter()));
        store().buildIndexes(session(), "", BUILD_INDEXES_DEFERRED);
        dumpIndexes(new PrintWriter(d = new StringWriter()));
        assertTrue(!a.toString().equals(b.toString()));
        assertEquals(a.toString(), c.toString());
        assertEquals(b.toString(), d.toString());
    }

    @Test
    public void testRebuildIndex() throws Exception {
        final TestData td = new TestData(3, 3, 3, 3);
        td.insertTestRows();
        final StringWriter a, b, c;
        dumpIndexes(new PrintWriter(a = new StringWriter()));
        store().deleteIndexes(new SessionImpl(), "");
        dumpIndexes(new PrintWriter(b = new StringWriter()));
        store().buildIndexes(session(), "", BUILD_INDEXES_DEFERRED);
        dumpIndexes(new PrintWriter(c = new StringWriter()));
        assertTrue(!a.toString().equals(b.toString()));
        assertEquals(a.toString(), c.toString());
    }

    private void dumpIndexes(final PrintWriter pw) throws Exception {
        RowDefCache rdc = store().getRowDefCache();
        for (final RowDef rowDef : rdc.getRowDefs()) {
            pw.println(rowDef);
            for (final IndexDef indexDef : rowDef.getIndexDefs()) {
                pw.println(indexDef);
                dumpIndex(indexDef, pw);
            }
        }
        pw.flush();

    }

    private PersistitStore getPersistitStore() {
        return (PersistitStore) store();
    }

    private TreeServiceImpl getTreeService() {
        return (TreeServiceImpl) serviceManager().getTreeService();
    }

    private Volume getDefaultVolume() throws PersistitException {
        return getTreeService().mappedVolume("default", SCHEMA_TREE_NAME);
    }

    private void dumpIndex(final IndexDef indexDef, final PrintWriter pw)
            throws Exception {
        final Exchange ex = getPersistitStore().getExchange(new SessionImpl(),
                                                            indexDef.getRowDef(), indexDef);
        ex.clear();
        while (ex.next(true)) {
            pw.println(ex.getKey());
        }
        pw.flush();
    }

    private boolean isGone(final TreeLink link) throws Exception {
        Volume volume = getDefaultVolume();
        final Tree tree = volume.getTree(link.getTreeName(), false);
        if (tree == null) {
            return true;
        }
        final Exchange exchange = getTreeService().getExchange(session(), link);
        exchange.clear();
        return !exchange.hasChildren();
    }
}
