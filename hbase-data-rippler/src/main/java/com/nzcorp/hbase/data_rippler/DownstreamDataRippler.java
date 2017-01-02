package com.nzcorp.hbase.data_rippler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

//remember to add the hbase dependencies to the pom file
@SuppressWarnings("unused")
public class DownstreamDataRippler extends BaseRegionObserver {
    private static final Log LOGGER = LogFactory.getLog(DownstreamDataRippler.class);
    /**
     * Connection to HBase
     */
    private Connection conn;
    /**
     * The table into which the values from the current table should be written into
     */
    private String destinationTable;
    /**
     * The table from which the child table rowkeys should be retrieved from
     */
    private String secondaryIndexTable;
    /**
     * The column family to find target keys for in the secondary index
     */
    private String secondaryIndexCF;
    /**
     * The column family name to use in the destination table
     */
    private String targetCf;
    /**
     * The column family name from which to collect values from
     */
    private String sourceCF;
    /**
     * Whether to write a ridiculously amount of logging information
     * Use with caution
     */
    private boolean f_debug;

    private static final double NANOS_TO_SECS = 1000000000.0;


    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        /**
         * The CoprocessorEnvironment.getConfiguration() will return a
         * Hadoop Configuration element as described here:
         * https://hadoop.apache.org/docs/r2.6.1/api/org/apache/hadoop/conf/Configuration.html
         *
         * The named arguments given after the last pipe separator when installing the coprocessor will be available on
         * the above configuration element
         */

        conn = ConnectionFactory.createConnection(env.getConfiguration());

        destinationTable = env.getConfiguration().get("destination_table");
        LOGGER.info(String.format("Using destination table %s", destinationTable));
        try {
            conn.getTable(TableName.valueOf(destinationTable));
        } catch (IOException ioe) {
            String err = "Table " + destinationTable + " does not exist";
            LOGGER.fatal(err, ioe);
            throw new IOException(err, ioe);
        }

        // the column family name to take all values from
        secondaryIndexTable = env.getConfiguration().get("secondary_index_table");

        secondaryIndexCF = env.getConfiguration().get("secondary_index_cf");
        if(secondaryIndexCF == null) {
            LOGGER.fatal("No 'secondary_index_cf' specified, cannot continue. Please set secondary_index_cf=some_sensible_value for the coprocessor");
        }

        // the column family name to take all values from
        sourceCF = env.getConfiguration().get("source_column_family");

        // the column family name to put values into in the destinationTable
        targetCf = env.getConfiguration().get("target_column_family");

        //option to run *expensive* debugging
        f_debug = Boolean.parseBoolean(env.getConfiguration().get("full_debug"));

        LOGGER.info("Initializing data rippler copying values from column family " + sourceCF + " to " + destinationTable + ":" + targetCf);
        LOGGER.info("Using secondary index " + secondaryIndexTable + ", column family: "+ secondaryIndexCF);

    }

    @Override
    public void postPut(final ObserverContext<RegionCoprocessorEnvironment> observerContext,
                        final Put put,
                        final WALEdit edit,
                        final Durability durability_enum)
            throws IOException {
        Table table = null;
        Table secTable = null;
        try {
            long startTime = System.nanoTime();
            double lapTime;

            final String sourceTable = observerContext.getEnvironment().getRegionInfo().getTable().getNameAsString();

            /**
             * The Mutation.getCellList is the method we need, as we want to get all qualifiers of a given column family
             * Unfortunately, the method is package-private, and we only have access to the subclass `Put` here, so we
             * access the method by using the Reflection API. Please note that this is a last resort tactic and that we
             * will try to argue with the HBase projects, that there is a use case for exposing this method in the
             * public API. As `Mutation::getCellList` is not in the public API, the method could disappear without
             * deprecation warnings (or warnings at all). It will be in 2.0.0, as far as we know.
             */
            final Method meth = Mutation.class.getDeclaredMethod("getCellList", byte[].class);
            meth.setAccessible(true);
            final List<Cell> list_of_cells = (List<Cell>) meth.invoke(put, sourceCF.getBytes());

            if (list_of_cells.isEmpty()) {
                LOGGER.info("No cells in this transaction");
                return;
            }
            LOGGER.debug("Found " + Integer.toString(list_of_cells.size()) + " cells in Put");


            final Map<String, List<byte[]>> keysCache = new HashMap<String, List<byte[]>>();


            if (f_debug) {
                for (Cell cell : list_of_cells) {
                    final byte[] rowKey = CellUtil.cloneRow(cell);
                    LOGGER.info(String.format("Found rowkey: %s", new String(rowKey)));
                }
            }

            table = conn.getTable(TableName.valueOf(destinationTable));
            secTable = conn.getTable(TableName.valueOf(secondaryIndexTable));

            for (Cell cell : list_of_cells) {
                final byte[] rowKey = CellUtil.cloneRow(cell);
                final byte[] family = targetCf.getBytes();
                final byte[] qualifier = CellUtil.cloneQualifier(cell);
                final byte[] value = CellUtil.cloneValue(cell);

                if (!keysCache.containsKey(new String(rowKey))) {
                    lapTime = (double) (System.nanoTime()) / NANOS_TO_SECS;

                    LOGGER.info(String.format("building cache for %s", new String(rowKey)));
                    keysCache.put(new String(rowKey), getTargetRowkeys(rowKey, secTable));
                    lapTime = (System.nanoTime() / NANOS_TO_SECS) - lapTime;
                    LOGGER.info(String.format("Built cache in %f seconds", lapTime));
                }


                LOGGER.debug(String.format("Looking up rowkey: %s", new String(rowKey)));

                List<byte[]> targetRowkeys = keysCache.get(new String(rowKey));

                if (targetRowkeys == null || targetRowkeys.size() == 0) {
                    LOGGER.warn("No target keys found for rowkey " + new String(rowKey));
                }else {
                    LOGGER.info(String.format("Found %s targetKeys", targetRowkeys.size()));
                    for (byte[] targetKey : targetRowkeys) {
                        LOGGER.trace("Put'ing into " + destinationTable + ": " + new String(targetKey));
                        Put targetData = new Put(targetKey).addColumn(family, qualifier, value);
                        LOGGER.info(String.format("Inserting from '%s', '%s' %s ==> '%s', '%s'  %s:%s",
                                sourceTable,
                                new String(rowKey),
                                sourceCF,
                                destinationTable,
                                new String(targetKey),
                                new String(family),
                                new String(qualifier)));
                        table.put(targetData);
                    }
                    lapTime = (double) (System.nanoTime() - startTime) / NANOS_TO_SECS;
                    LOGGER.info(String.format("Wrote %s items to %s in %f seconds from start", targetRowkeys.size(), destinationTable, lapTime));
                }
            }
            secTable.close();

            long endTime = System.nanoTime();
            double elapsedTime = (double) (endTime - startTime) / NANOS_TO_SECS;
            LOGGER.info(String.format("Exiting postPut, took %f seconds from start", elapsedTime));

        } catch (IllegalArgumentException ex) {
            LOGGER.fatal("During the postPut operation, something went horribly wrong", ex);

            throw new IllegalArgumentException(ex);
        } catch (NoSuchMethodException e) {
            LOGGER.error("In trying to acquire reference to the Mutation::getCellList, an error occurred", e);
        } catch (IllegalAccessException e) {
            LOGGER.error("In trying to assign the reference to the Mutation::getCellList to a variable, an error occurred", e);
        } catch (InvocationTargetException e) {
            LOGGER.error("In trying to invoke the Mutation::getCellList, an error occurred", e);
        } finally {
            /**
             * Clean up resources that may have been left open
             */
            if (table != null) {
                table.close();
            }
            if (secTable != null) {
                secTable.close();
            }
        }
    }

    private List<byte[]> getTargetRowkeys(byte[] rowKey, Table secTable) throws IOException {

        List<byte[]> targetKeys = new ArrayList<byte[]>();
        Result result = secTable.get(new Get(rowKey));
        List<Cell> cellList = result.listCells();

	if( cellList != null && cellList.size() > 0 ) {
	    for( Cell cell: cellList ) {
		byte[] cf = CellUtil.cloneFamily(cell);
		if (Arrays.equals( cf, secondaryIndexCF.getBytes())) {
		    LOGGER.info(String.format("got column %s", new String(CellUtil.cloneQualifier(cell))));
		    targetKeys.add(CellUtil.cloneQualifier(cell));
		}
	    }
	} else {
	    LOGGER.warn( String.format("Found no targetKeys for %s", new String(rowKey)));
	}
	    return targetKeys;
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
        conn.close();
    }
}