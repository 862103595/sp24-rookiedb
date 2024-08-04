package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;
import edu.berkeley.cs186.database.table.Record;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        Long prevLSN = transactionTable.get(transNum).lastLSN;
        CommitTransactionLogRecord record = new CommitTransactionLogRecord(transNum, prevLSN);
        Long newLSN = logManager.appendToLog(record);
        logManager.flushToLSN(newLSN);
        transactionTable.get(transNum).lastLSN = newLSN;
        transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMMITTING);
        return newLSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        Long prevLSN = transactionTable.get(transNum).lastLSN;
        AbortTransactionLogRecord record = new AbortTransactionLogRecord(transNum, prevLSN);
        Long newLSN = logManager.appendToLog(record);
        transactionTable.get(transNum).lastLSN = newLSN;
        transactionTable.get(transNum).transaction.setStatus(Transaction.Status.ABORTING);
        return newLSN;
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        if (transactionTable.get(transNum).transaction.getStatus() == Transaction.Status.ABORTING) {
            rollbackToLSN(transNum, 0L);
        }
        Long prevLSN = transactionTable.get(transNum).lastLSN;
        transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMPLETE);
        transactionTable.remove(transNum);
        LogRecord end = new EndTransactionLogRecord(transNum, prevLSN);
        Long newLSN = logManager.appendToLog(end);
        return newLSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Append the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
        while (currentLSN > LSN) {
            LogRecord record = logManager.fetchLogRecord(currentLSN);
            if(record.isUndoable()) {
                LogRecord CLR = record.undo(lastRecordLSN);
                lastRecordLSN = logManager.appendToLog(CLR);
                CLR.redo(this, diskSpaceManager, bufferManager);
                currentLSN = CLR.getUndoNextLSN().get();
            }
            else {
                currentLSN = record.getPrevLSN().get();
            }

        }
        transactionEntry.lastLSN = lastRecordLSN;
        // TODO(proj5) implement the rollback logic described above
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        Long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new UpdatePageLogRecord(transNum, pageNum, prevLSN, pageOffset, before, after);
        Long newLSN = logManager.appendToLog(record);
        transactionEntry.lastLSN = newLSN;
        dirtyPageTable.putIfAbsent(pageNum, newLSN);
        return newLSN;
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        rollbackToLSN(transNum, savepointLSN);
        return;
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        chkptDPT.putAll(dirtyPageTable);
        for (Map.Entry<Long, TransactionTableEntry> entry : transactionTable.entrySet()) {
            long transId = entry.getKey();
            TransactionTableEntry txnEntry = entry.getValue();
            chkptTxnTable.put(transId, new Pair<>(txnEntry.transaction.getStatus(), txnEntry.lastLSN));
        }
        while (!EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size(), chkptTxnTable.size())){
            Map<Long, Long> part_chkptDPT = new HashMap<>();
            Map<Long, Pair<Transaction.Status, Long>> part_chkptTxnTable = new HashMap<>();
            Iterator<Map.Entry<Long, Long>> dptIterator = chkptDPT.entrySet().iterator();
            Iterator<Map.Entry<Long, Pair<Transaction.Status, Long>>> txnIterator = chkptTxnTable.entrySet().iterator();
            while (txnIterator.hasNext() && !EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size(), chkptTxnTable.size())) {
                Map.Entry<Long, Pair<Transaction.Status, Long>> entry = txnIterator.next();
                part_chkptTxnTable.put(entry.getKey(), entry.getValue());
                txnIterator.remove();
            }
            while (dptIterator.hasNext() && !EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size(), chkptTxnTable.size())) {
                Map.Entry<Long, Long> entry = dptIterator.next();
                part_chkptDPT.put(entry.getKey(), entry.getValue());
                dptIterator.remove();
            }
            LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
            logManager.appendToLog(endRecord);
            flushToLSN(endRecord.getLSN());
            chkptDPT = part_chkptDPT;
            chkptTxnTable = part_chkptTxnTable;
        }

        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related (getPageNum is present), update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records in the log are processed, for each ttable entry:
     *  - if COMMITTING: clean up the transaction, change status to COMPLETE,
     *    remove from the ttable, and append an end record
     *  - if RUNNING: change status to RECOVERY_ABORTING, and append an abort
     *    record
     *  - if RECOVERY_ABORTING: no action needed
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        // TODO(proj5): implement
        Iterator<LogRecord> iterator = logManager.scanFrom(LSN);
        while (iterator.hasNext()) {
            LogRecord logRecord = iterator.next();
            if(logRecord.getTransNum().isPresent()){
                Long trannum = logRecord.getTransNum().get();
                if(!transactionTable.containsKey(trannum)){
                    Transaction newtransaction = newTransaction.apply(trannum);
                    startTransaction(newtransaction);
                }
                transactionTable.get(trannum).lastLSN = logRecord.getLSN();
            }
            if(logRecord.getPageNum().isPresent()) {
                if(logRecord.getType().equals(LogType.UPDATE_PAGE) || logRecord.getType().equals(LogType.UNDO_UPDATE_PAGE)) {
                    dirtyPageTable.putIfAbsent(logRecord.getPageNum().get(), logRecord.getLSN());
                }
                else if(logRecord.getType().equals(LogType.FREE_PAGE) || logRecord.getType().equals(LogType.UNDO_ALLOC_PAGE)) {
                    dirtyPageTable.remove(logRecord.getPageNum().get());
                }
            }
            if(logRecord.getType().equals(LogType.COMMIT_TRANSACTION) ||logRecord.getType().equals(LogType.ABORT_TRANSACTION) ||logRecord.getType().equals(LogType.END_TRANSACTION) ) {
                Long trannum = logRecord.getTransNum().get();
                if(logRecord.getType().equals(LogType.COMMIT_TRANSACTION)) {
                    transactionTable.get(trannum).transaction.setStatus(Transaction.Status.COMMITTING);
                }
                else if(logRecord.getType().equals(LogType.ABORT_TRANSACTION)) {
                    transactionTable.get(trannum).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                }
                else {
                    transactionTable.get(trannum).transaction.cleanup();
                    transactionTable.get(trannum).transaction.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.remove(trannum);
                    endedTransactions.add(trannum);
                }
            }
            if(logRecord.getType().equals(LogType.END_CHECKPOINT)) {
                Map<Long, Long> dpt = logRecord.getDirtyPageTable();
                for(Map.Entry<Long, Long> entry : dpt.entrySet()) {
                    dirtyPageTable.put(entry.getKey(), entry.getValue());
                }
                Map<Long, Pair<Transaction.Status, Long>> xnt = logRecord.getTransactionTable();
                for(Map.Entry<Long, Pair<Transaction.Status, Long>> entry : xnt.entrySet()) {
                    Long trannum = entry.getKey();
                    Pair<Transaction.Status, Long> pair = entry.getValue();
                    if(endedTransactions.contains(trannum)) {
                        continue;
                    }
                    else if(!transactionTable.containsKey(trannum)) {
                        Transaction newtransaction = newTransaction.apply(trannum);
                        startTransaction(newtransaction);
                        transactionTable.get(trannum).lastLSN =pair.getSecond();
                        transactionTable.get(trannum).transaction.setStatus(pair.getFirst());
                        if(pair.getFirst().equals(Transaction.Status.ABORTING)) {
                            transactionTable.get(trannum).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                        }
                    }
                    else{
                        if(pair.getSecond().compareTo(transactionTable.get(trannum).lastLSN) > 0) {
                            transactionTable.get(trannum).lastLSN = pair.getSecond();
                        }
                        if(pair.getFirst().compareTo(transactionTable.get(trannum).transaction.getStatus()) > 0) {
                            transactionTable.get(trannum).transaction.setStatus(pair.getFirst());
                            if(pair.getFirst().equals(Transaction.Status.ABORTING)) {
                                transactionTable.get(trannum).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                            }
                        }
                    }
                }
            }

        }
        for (TransactionTableEntry entry : transactionTable.values()) {
            switch (entry.transaction.getStatus()) {
                case COMMITTING:
                    entry.transaction.cleanup();
                    entry.transaction.setStatus(Transaction.Status.COMPLETE);
                    entry.lastLSN = logManager.appendToLog(new EndTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN));

                    transactionTable.remove(entry.transaction.getTransNum());

                    break;
                case RUNNING:
                    entry.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                    entry.lastLSN= logManager.appendToLog(new AbortTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN));
                    break;
                case RECOVERY_ABORTING:
                    // 通常不需要任何操作，因为事务已处于恢复中的中止状态
                    break;
                default:
                    // 处理其他状态，如果有必要
                    break;
            }
        }
        return;
    }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - partition-related (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement
        Long minlsn = null;
        for(Long LSN: dirtyPageTable.values()) {
            if(minlsn == null || LSN < minlsn) {
                minlsn = LSN;
            }
        }
        if(minlsn != null) {
            Iterator<LogRecord> iterator = logManager.scanFrom(minlsn);
            while(iterator.hasNext()) {
                LogRecord record = iterator.next();
                if(record.isRedoable()) {
                    switch (record.getType()) {
                        case ALLOC_PART:
                        case FREE_PART:
                        case UNDO_ALLOC_PART:
                        case UNDO_FREE_PART:
                        case ALLOC_PAGE:
                        case UNDO_FREE_PAGE:
                            // Always redo these types of records
                            record.redo(this, diskSpaceManager, bufferManager);
                            break;

                        case UPDATE_PAGE:
                        case UNDO_UPDATE_PAGE:
                        case FREE_PAGE:
                        case UNDO_ALLOC_PAGE:
                            Long pagenum = record.getPageNum().get();
                            Long pageLSN;
                            Page page = bufferManager.fetchPage(new DummyLockContext(), pagenum);
                            try {
                                pageLSN = page.getPageLSN();
                                if (dirtyPageTable.containsKey(pagenum) &&
                                        record.getLSN() >= dirtyPageTable.get(pagenum) &&
                                        pageLSN < record.getLSN()) {
                                    record.redo(this, diskSpaceManager, bufferManager);
                                }
                            } finally {
                                page.unpin();
                            }
                            break;
                    }


                }
            }
        }
        return;
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting
     * transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and append the appropriate CLR
     * - replace the entry with a new one, using the undoNextLSN if available,
     *   if the prevLSN otherwise.
     * - if the new LSN is 0, clean up the transaction, set the status to complete,
     *   and remove from transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        PriorityQueue<Pair<Long, Long>> queue = new PriorityQueue<>(new PairFirstReverseComparator<>());

        for(Map.Entry<Long, TransactionTableEntry> entry : transactionTable.entrySet()) {
            LogRecord lastRecord = logManager.fetchLogRecord(entry.getValue().lastLSN);
            long lastRecordLSN = lastRecord.getLSN();
            long beginLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
            queue.add(new Pair<>(beginLSN, entry.getKey()));
        }
        while (!queue.isEmpty()) {
            Pair<Long, Long> pair = queue.poll();
            Long lsn= pair.getFirst();
            Long tranNum = pair.getSecond();
            LogRecord record = logManager.fetchLogRecord(lsn);
            if(record.isUndoable()) {
                LogRecord comRec = record.undo(transactionTable.get(tranNum).lastLSN);
                Long newlsn = logManager.appendToLog(comRec);
                transactionTable.get(tranNum).lastLSN = newlsn;
                comRec.redo(this, diskSpaceManager, bufferManager);
                Long undoNextLSN = comRec.getUndoNextLSN().get();
                if(!undoNextLSN.equals(0L)) {
                    queue.add(new Pair<>(undoNextLSN, tranNum));
                }
                else{
                    TransactionTableEntry entry = transactionTable.get(tranNum);
                    entry.transaction.cleanup();
                    entry.transaction.setStatus(Transaction.Status.COMPLETE);
                    entry.lastLSN = logManager.appendToLog(new EndTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN));

                    transactionTable.remove(entry.transaction.getTransNum());

                }
            }
            else {
                Long prevLSN = record.getPrevLSN().get();
                if(!prevLSN.equals(0L)) {
                    queue.add(new Pair<>(prevLSN, tranNum));
                }
                else{
                    TransactionTableEntry entry = transactionTable.get(tranNum);
                    entry.transaction.cleanup();
                    entry.transaction.setStatus(Transaction.Status.COMPLETE);
                    entry.lastLSN = logManager.appendToLog(new EndTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN));

                    transactionTable.remove(entry.transaction.getTransNum());

                }
            }


        }
        return;
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}
