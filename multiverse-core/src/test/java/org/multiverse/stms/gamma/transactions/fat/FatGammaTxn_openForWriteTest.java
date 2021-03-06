package org.multiverse.stms.gamma.transactions.fat;

import org.junit.Before;
import org.junit.Test;
import org.multiverse.api.IsolationLevel;
import org.multiverse.api.LockMode;
import org.multiverse.api.TxnStatus;
import org.multiverse.api.exceptions.*;
import org.multiverse.api.functions.Functions;
import org.multiverse.stms.gamma.GammaConstants;
import org.multiverse.stms.gamma.GammaStm;
import org.multiverse.stms.gamma.transactionalobjects.GammaTxnLong;
import org.multiverse.stms.gamma.transactionalobjects.Tranlocal;
import org.multiverse.stms.gamma.transactions.GammaTxn;
import org.multiverse.stms.gamma.transactions.GammaTxnConfig;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.multiverse.TestUtils.assertIsAborted;
import static org.multiverse.TestUtils.assertIsActive;
import static org.multiverse.stms.gamma.GammaTestUtils.*;

public abstract class FatGammaTxn_openForWriteTest<T extends GammaTxn> implements GammaConstants {

    protected GammaStm stm;

    @Before
    public void setUp() {
        stm = new GammaStm();
    }

    protected abstract T newTransaction(GammaTxnConfig config);

    protected abstract T newTransaction();

    protected abstract int getMaxCapacity();

    @Test
    public void whenArrive() {
        //the mono transaction doesn't support (or need) the richmans conflict
        assumeTrue(getMaxCapacity() > 1);

        GammaTxnLong ref = new GammaTxnLong(stm);

        GammaTxnConfig config = new GammaTxnConfig(stm)
                .setMaximumPoorMansConflictScanLength(0);

        T tx = newTransaction(config);
        ref.openForWrite(tx, LOCKMODE_NONE);

        assertSurplus(ref, 1);
    }

    @Test
    public void whenStmMismatch() {
        GammaStm otherStm = new GammaStm();
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(otherStm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxn tx = stm.newDefaultTxn();

        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (StmMismatchException expected) {
        }

        assertIsAborted(tx);
        assertRefHasNoLocks(ref);
        assertVersionAndValue(ref, initialVersion, initialValue);
    }


    @Test
    public void whenAlreadyOpenedForConstruction() {
        T tx = newTransaction();
        GammaTxnLong ref = new GammaTxnLong(tx, 0);

        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_NONE);

        assertNotNull(tranlocal);
        assertSame(ref, tranlocal.owner);
        assertEquals(LOCKMODE_EXCLUSIVE, tranlocal.getLockMode());
        assertEquals(TRANLOCAL_CONSTRUCTING, tranlocal.getMode());
        assertRefHasExclusiveLock(ref, tx);
        assertIsActive(tx);
    }

    @Test
    public void whenAlreadyOpenedForCommute() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        ref.commute(tx, Functions.incLongFunction());
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_NONE);

        assertNotNull(tranlocal);
        assertSame(ref, tranlocal.owner);
        assertEquals(LOCKMODE_NONE, tranlocal.getLockMode());
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
        assertIsActive(tx);
        assertEquals(11, tranlocal.long_value);
        assertEquals(10, tranlocal.long_oldValue);
        assertRefHasNoLocks(ref);
        assertVersionAndValue(ref, initialVersion, initialValue);
    }

     @Test
    public void whenAlreadyOpenedForCommuteAndLockingConflicts() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        ref.commute(tx, Functions.incLongFunction());
        Tranlocal tranlocal = tx.locate(ref);

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Exclusive);

        try{
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        }catch(ReadWriteConflict expected){

        }

        assertNotNull(tranlocal);
        assertNull(tranlocal.owner);
        assertEquals(LOCKMODE_NONE, tranlocal.getLockMode());
        assertIsAborted(tx);
        assertRefHasExclusiveLock(ref, otherTx);
        assertVersionAndValue(ref, initialVersion, initialValue);
    }
    @Test
    public void whenTransactionAbortOnly_thenWriteStillPossible() {
        GammaTxnLong ref = new GammaTxnLong(stm, 0);

        GammaTxn tx = stm.newDefaultTxn();
        tx.setAbortOnly();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_NONE);

        assertNotNull(tranlocal);
        assertTrue(tx.isAbortOnly());
        assertIsActive(tx);
    }

    @Test
    public void whenTransactionAbortOnly_thenRereadStillPossible() {
        GammaTxnLong ref = new GammaTxnLong(stm, 0);

        GammaTxn tx = stm.newDefaultTxn();
        Tranlocal read = ref.openForWrite(tx, LOCKMODE_NONE);
        tx.setAbortOnly();
        Tranlocal reread = ref.openForWrite(tx, LOCKMODE_NONE);

        assertSame(read, reread);
        assertTrue(tx.isAbortOnly());
        assertIsActive(tx);
    }

  @Test
    public void whenWriteFirstAndExclusivelyLockedByOtherAndThenReWrite_thenNoProblem() {
        whenWriteFirstAndExclusivelyLockedByOtherAndThenReWrite_thenNoProblem(LockMode.None);
        whenWriteFirstAndExclusivelyLockedByOtherAndThenReWrite_thenNoProblem(LockMode.Read);
        whenWriteFirstAndExclusivelyLockedByOtherAndThenReWrite_thenNoProblem(LockMode.Write);
        whenWriteFirstAndExclusivelyLockedByOtherAndThenReWrite_thenNoProblem(LockMode.Exclusive);
    }

    public void whenWriteFirstAndExclusivelyLockedByOtherAndThenReWrite_thenNoProblem(LockMode lockMode) {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxn tx = newTransaction();
        Tranlocal read = ref.openForRead(tx, LOCKMODE_NONE);

        GammaTxn otherTx = newTransaction();
        ref.getLock().acquire(otherTx, lockMode);

        Tranlocal read2 = ref.openForWrite(tx, LOCKMODE_NONE);

        assertIsActive(tx);
        assertSame(read, read2);
        assertRefHasLockMode(ref, otherTx, lockMode.asInt());
        assertVersionAndValue(ref, initialVersion, initialValue);
    }


    @Test
    public void whenOverflowing() {
        int maxCapacity = getMaxCapacity();
        assumeTrue(maxCapacity < Integer.MAX_VALUE);

        T tx = newTransaction();
        System.out.println(tx.getConfig().getSpeculativeConfiguration().minimalLength);

        for (int k = 0; k < maxCapacity; k++) {
            GammaTxnLong ref = new GammaTxnLong(stm, 0);
            ref.openForWrite(tx, LOCKMODE_NONE);
        }

        GammaTxnLong ref = new GammaTxnLong(stm, 0);
        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (SpeculativeConfigurationError expected) {
        }

        assertEquals(TxnStatus.Aborted, tx.getStatus());
        assertEquals(maxCapacity + 1, tx.getConfig().getSpeculativeConfiguration().minimalLength);
    }

    @Test
    public void whenReadonlyTransaction_thenReadonlyException() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxnConfig config = new GammaTxnConfig(stm);
        config.readonly = true;
        T tx = newTransaction(config);

        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (ReadonlyException expected) {

        }

        assertEquals(TxnStatus.Aborted, tx.getStatus());
        assertEquals(initialValue, ref.long_value);
        assertEquals(initialVersion, ref.version);
    }

    @Test
    public void whenNotOpenedBefore() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_NONE);

        assertNotNull(tranlocal);
        assertSame(ref, tranlocal.owner);
        assertEquals(initialVersion, tranlocal.version);
        assertEquals(initialValue, tranlocal.long_value);
        assertEquals(initialValue, tranlocal.long_oldValue);
        assertTrue(tranlocal.isWrite());
        assertTrue(tx.hasWrites());
    }

    @Test
    public void whenRefAlreadyOpenedForRead() {
        whenRefAlreadyOpenedForRead(LockMode.None, LockMode.None, LockMode.None);
        whenRefAlreadyOpenedForRead(LockMode.None, LockMode.Read, LockMode.Read);
        whenRefAlreadyOpenedForRead(LockMode.None, LockMode.Write, LockMode.Write);
        whenRefAlreadyOpenedForRead(LockMode.None, LockMode.Exclusive, LockMode.Exclusive);

        whenRefAlreadyOpenedForRead(LockMode.Read, LockMode.None, LockMode.Read);
        whenRefAlreadyOpenedForRead(LockMode.Read, LockMode.Read, LockMode.Read);
        whenRefAlreadyOpenedForRead(LockMode.Read, LockMode.Write, LockMode.Write);
        whenRefAlreadyOpenedForRead(LockMode.Read, LockMode.Exclusive, LockMode.Exclusive);

        whenRefAlreadyOpenedForRead(LockMode.Write, LockMode.None, LockMode.Write);
        whenRefAlreadyOpenedForRead(LockMode.Write, LockMode.Read, LockMode.Write);
        whenRefAlreadyOpenedForRead(LockMode.Write, LockMode.Write, LockMode.Write);
        whenRefAlreadyOpenedForRead(LockMode.Write, LockMode.Exclusive, LockMode.Exclusive);

        whenRefAlreadyOpenedForRead(LockMode.Exclusive, LockMode.None, LockMode.Exclusive);
        whenRefAlreadyOpenedForRead(LockMode.Exclusive, LockMode.Read, LockMode.Exclusive);
        whenRefAlreadyOpenedForRead(LockMode.Exclusive, LockMode.Write, LockMode.Exclusive);
        whenRefAlreadyOpenedForRead(LockMode.Exclusive, LockMode.Exclusive, LockMode.Exclusive);
    }

    public void whenRefAlreadyOpenedForRead(LockMode readLockMode, LockMode writeLockMode, LockMode expectedLockMode) {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxn tx = newTransaction();
        Tranlocal first = ref.openForRead(tx, readLockMode.asInt());
        Tranlocal second = ref.openForWrite(tx, writeLockMode.asInt());

        assertSame(first, second);
        assertNotNull(second);
        assertSame(ref, second.owner);
        assertEquals(initialVersion, second.version);
        assertEquals(initialValue, second.long_value);
        assertEquals(initialValue, second.long_oldValue);
        assertLockMode(ref, expectedLockMode);
        assertTrue(second.isWrite());
        assertTrue(tx.hasWrites());
    }

    @Test
    public void whenRefAlreadyOpenedForWrite() {
        whenRefAlreadyOpenedForWrite(LockMode.None, LockMode.None, LockMode.None);
        whenRefAlreadyOpenedForWrite(LockMode.None, LockMode.Read, LockMode.Read);
        whenRefAlreadyOpenedForWrite(LockMode.None, LockMode.Write, LockMode.Write);
        whenRefAlreadyOpenedForWrite(LockMode.None, LockMode.Exclusive, LockMode.Exclusive);

        whenRefAlreadyOpenedForWrite(LockMode.Read, LockMode.None, LockMode.Read);
        whenRefAlreadyOpenedForWrite(LockMode.Read, LockMode.Read, LockMode.Read);
        whenRefAlreadyOpenedForWrite(LockMode.Read, LockMode.Write, LockMode.Write);
        whenRefAlreadyOpenedForWrite(LockMode.Read, LockMode.Exclusive, LockMode.Exclusive);

        whenRefAlreadyOpenedForWrite(LockMode.Write, LockMode.None, LockMode.Write);
        whenRefAlreadyOpenedForWrite(LockMode.Write, LockMode.Read, LockMode.Write);
        whenRefAlreadyOpenedForWrite(LockMode.Write, LockMode.Write, LockMode.Write);
        whenRefAlreadyOpenedForWrite(LockMode.Write, LockMode.Exclusive, LockMode.Exclusive);

        whenRefAlreadyOpenedForWrite(LockMode.Exclusive, LockMode.None, LockMode.Exclusive);
        whenRefAlreadyOpenedForWrite(LockMode.Exclusive, LockMode.Read, LockMode.Exclusive);
        whenRefAlreadyOpenedForWrite(LockMode.Exclusive, LockMode.Write, LockMode.Exclusive);
        whenRefAlreadyOpenedForWrite(LockMode.Exclusive, LockMode.Exclusive, LockMode.Exclusive);
    }

    public void whenRefAlreadyOpenedForWrite(LockMode firstWriteLockMode, LockMode secondWriteLockMode, LockMode expectedLockMode) {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxn tx = newTransaction();
        Tranlocal first = ref.openForWrite(tx, firstWriteLockMode.asInt());
        Tranlocal second = ref.openForWrite(tx, secondWriteLockMode.asInt());

        assertSame(first, second);
        assertSame(ref, second.owner);
        assertEquals(initialVersion, second.version);
        assertEquals(initialValue, second.long_value);
        assertEquals(initialValue, second.long_oldValue);
        assertLockMode(ref, expectedLockMode);
        assertTrue(second.isWrite());
        assertTrue(tx.hasWrites());
    }

    @Test
    public void readConsistency_whenNotConsistent() {
        assumeTrue(getMaxCapacity() > 1);

        GammaTxnLong ref1 = new GammaTxnLong(stm, 0);
        GammaTxnLong ref2 = new GammaTxnLong(stm, 0);

        GammaTxn tx = newTransaction();
        ref1.openForWrite(tx, LOCKMODE_NONE);

        ref1.atomicIncrementAndGet(1);

        try {
            ref2.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
    }

// ====================== lock level ========================================

    @Test
    public void lockLevel() {
        lockLevel(LOCKMODE_NONE, LOCKMODE_NONE, LOCKMODE_NONE);
        lockLevel(LOCKMODE_NONE, LOCKMODE_READ, LOCKMODE_READ);
        lockLevel(LOCKMODE_NONE, LOCKMODE_WRITE, LOCKMODE_WRITE);
        lockLevel(LOCKMODE_NONE, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);

        lockLevel(LOCKMODE_READ, LOCKMODE_NONE, LOCKMODE_READ);
        lockLevel(LOCKMODE_READ, LOCKMODE_READ, LOCKMODE_READ);
        lockLevel(LOCKMODE_READ, LOCKMODE_WRITE, LOCKMODE_WRITE);
        lockLevel(LOCKMODE_READ, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);

        lockLevel(LOCKMODE_WRITE, LOCKMODE_NONE, LOCKMODE_WRITE);
        lockLevel(LOCKMODE_WRITE, LOCKMODE_READ, LOCKMODE_WRITE);
        lockLevel(LOCKMODE_WRITE, LOCKMODE_WRITE, LOCKMODE_WRITE);
        lockLevel(LOCKMODE_WRITE, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);

        lockLevel(LOCKMODE_EXCLUSIVE, LOCKMODE_NONE, LOCKMODE_EXCLUSIVE);
        lockLevel(LOCKMODE_EXCLUSIVE, LOCKMODE_READ, LOCKMODE_EXCLUSIVE);
        lockLevel(LOCKMODE_EXCLUSIVE, LOCKMODE_WRITE, LOCKMODE_EXCLUSIVE);
        lockLevel(LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);
    }

    public void lockLevel(int transactionWriteLockMode, int writeLockMode, int expectedLockMode) {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxnConfig config = new GammaTxnConfig(stm);
        config.writeLockModeAsInt = transactionWriteLockMode;
        GammaTxn tx = newTransaction(config);
        Tranlocal tranlocal = ref.openForWrite(tx, writeLockMode);

        assertEquals(expectedLockMode, tranlocal.getLockMode());
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
        assertIsActive(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasLockMode(ref, tx, expectedLockMode);
    }

    // ======================= lock upgrade ===================================

    @Test
    public void lockUpgrade() {
        lockUpgrade(LOCKMODE_NONE, LOCKMODE_NONE, LOCKMODE_NONE);
        lockUpgrade(LOCKMODE_NONE, LOCKMODE_READ, LOCKMODE_READ);
        lockUpgrade(LOCKMODE_NONE, LOCKMODE_WRITE, LOCKMODE_WRITE);
        lockUpgrade(LOCKMODE_NONE, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);

        lockUpgrade(LOCKMODE_READ, LOCKMODE_NONE, LOCKMODE_READ);
        lockUpgrade(LOCKMODE_READ, LOCKMODE_READ, LOCKMODE_READ);
        lockUpgrade(LOCKMODE_READ, LOCKMODE_WRITE, LOCKMODE_WRITE);
        lockUpgrade(LOCKMODE_READ, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);

        lockUpgrade(LOCKMODE_WRITE, LOCKMODE_NONE, LOCKMODE_WRITE);
        lockUpgrade(LOCKMODE_WRITE, LOCKMODE_READ, LOCKMODE_WRITE);
        lockUpgrade(LOCKMODE_WRITE, LOCKMODE_WRITE, LOCKMODE_WRITE);
        lockUpgrade(LOCKMODE_WRITE, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);

        lockUpgrade(LOCKMODE_EXCLUSIVE, LOCKMODE_NONE, LOCKMODE_EXCLUSIVE);
        lockUpgrade(LOCKMODE_EXCLUSIVE, LOCKMODE_READ, LOCKMODE_EXCLUSIVE);
        lockUpgrade(LOCKMODE_EXCLUSIVE, LOCKMODE_WRITE, LOCKMODE_EXCLUSIVE);
        lockUpgrade(LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE, LOCKMODE_EXCLUSIVE);
    }

    public void lockUpgrade(int firstMode, int secondLockMode, int expectedLockMode) {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        GammaTxn tx = newTransaction();
        ref.openForWrite(tx, firstMode);
        Tranlocal tranlocal = ref.openForWrite(tx, secondLockMode);

        assertEquals(expectedLockMode, tranlocal.getLockMode());
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
        assertIsActive(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasLockMode(ref, tx, expectedLockMode);
    }

    // ===================== locking ============================================

    @Test
    public void locking_noLockRequired_whenLockedForReadByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Read);

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_NONE);

        assertEquals(LOCKMODE_NONE, tranlocal.getLockMode());
        assertIsActive(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasReadLock(ref, otherTx);
        assertReadLockCount(ref, 1);
        assertEquals(ref, tranlocal.owner);
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
    }

    @Test
    public void locking_noLockRequired_whenLockedForWriteByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Write);

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_NONE);

        assertIsActive(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasWriteLock(ref, otherTx);
        assertEquals(ref, tranlocal.owner);
        assertEquals(LOCKMODE_NONE, tranlocal.getLockMode());
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
    }

    @Test
    public void locking_noLockReqyired_whenLockedForCommitByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Exclusive);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasExclusiveLock(ref, otherTx);
    }

    @Test
    public void locking_readLockRequired_whenFree() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_READ);

        assertEquals(LOCKMODE_READ, tranlocal.getLockMode());
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasReadLock(ref, tx);
        assertReadLockCount(ref, 1);
        assertEquals(ref, tranlocal.owner);
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
    }

    @Test
    public void locking_readLockRequired_whenLockedForReadByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Read);

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_READ);

        assertEquals(LOCKMODE_READ, tranlocal.getLockMode());
        assertIsActive(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasReadLock(ref, otherTx);
        assertReadLockCount(ref, 2);
        assertEquals(ref, tranlocal.owner);
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
    }

    @Test
    public void locking_readLockRequired_whenLockedForWriteByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Write);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_READ);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasWriteLock(ref, otherTx);
    }

    @Test
    public void locking_readLockReqyired_whenLockedForCommitByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Exclusive);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_READ);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasExclusiveLock(ref, otherTx);
    }

    @Test
    public void locking_writeLockRequired_whenFree() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_WRITE);

        assertEquals(LOCKMODE_WRITE, tranlocal.getLockMode());
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasWriteLock(ref, tx);
        assertEquals(ref, tranlocal.owner);
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
    }

    @Test
    public void locking_writeLockRequired_whenLockedForReadByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Read);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_WRITE);
            fail();
        } catch (ReadWriteConflict expected) {

        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasReadLock(ref, otherTx);
        assertReadLockCount(ref, 1);
    }

    @Test
    public void locking_writeLockRequired_whenLockedForWriteByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Write);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_WRITE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasWriteLock(ref, otherTx);
    }

    @Test
    public void locking_writeLockRequired_whenLockedForCommitByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Exclusive);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_WRITE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasExclusiveLock(ref, otherTx);
    }

    @Test
    public void locking_exclusiveLockRequired_whenFree() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        Tranlocal tranlocal = ref.openForWrite(tx, LOCKMODE_EXCLUSIVE);

        assertEquals(LOCKMODE_EXCLUSIVE, tranlocal.getLockMode());
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasExclusiveLock(ref, tx);
        assertEquals(ref, tranlocal.owner);
        assertEquals(TRANLOCAL_WRITE, tranlocal.getMode());
    }

    @Test
    public void locking_exclusiveLockRequired_whenLockedForReadByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Read);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_EXCLUSIVE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasReadLock(ref, otherTx);
        assertReadLockCount(ref, 1);
    }

    @Test
    public void locking_exclusiveLockRequired_whenLockedForWriteByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Write);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_EXCLUSIVE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasWriteLock(ref, otherTx);
    }

    @Test
    public void locking_exclusiveLockReqyired_whenLockedForCommitByOther() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T otherTx = newTransaction();
        ref.getLock().acquire(otherTx, LockMode.Exclusive);

        T tx = newTransaction();
        try {
            ref.openForWrite(tx, LOCKMODE_EXCLUSIVE);
            fail();
        } catch (ReadWriteConflict expected) {
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasExclusiveLock(ref, otherTx);
    }

    //====================================================
     @Test
    public void whenRepeatableReadIsolationLevel(){
         assumeTrue(getMaxCapacity()>1);

        long initialValue = 1;
        GammaTxnLong ref1 = new GammaTxnLong(stm, initialValue);
        GammaTxnLong ref2 = new GammaTxnLong(stm, initialValue);

        GammaTxnConfig config = new GammaTxnConfig(stm)
                .setIsolationLevel(IsolationLevel.RepeatableRead);
        T tx = newTransaction(config);

        ref1.openForWrite(tx, LOCKMODE_NONE);
        ref1.atomicIncrementAndGet(1);
        ref2.openForWrite(tx, LOCKMODE_NONE);

        assertIsActive(tx);
    }

    // ==========================================

     @Test
    public void commuting_whenCommuting_thenFailure() {
        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        T tx = newTransaction();
        tx.evaluatingCommute = true;

        try{
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        }catch(IllegalCommuteException expected){
        }

        assertIsAborted(tx);
        assertVersionAndValue(ref, initialVersion, initialValue);
        assertRefHasNoLocks(ref);
    }

    // ================================================================


    @Test
    public void whenTransactionPrepared_thenPreparedTxnException() {
        GammaTxn tx = newTransaction();
        tx.prepare();

        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (PreparedTxnException expected) {
        }

        assertIsAborted(tx);
        assertEquals(initialValue, ref.long_value);
        assertEquals(initialVersion, ref.version);
    }

    @Test
    public void whenTransactionAlreadyAborted_thenDeadTxnException() {
        GammaTxn tx = newTransaction();
        tx.abort();

        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (DeadTxnException expected) {
        }

        assertEquals(initialValue, ref.long_value);
        assertEquals(initialVersion, ref.version);
    }

    @Test
    public void whenTransactionAlreadyCommitted_thenDeadTxnException() {
        GammaTxn tx = newTransaction();
        tx.commit();

        long initialValue = 10;
        GammaTxnLong ref = new GammaTxnLong(stm, initialValue);
        long initialVersion = ref.getVersion();

        try {
            ref.openForWrite(tx, LOCKMODE_NONE);
            fail();
        } catch (DeadTxnException expected) {
        }

        assertEquals(initialValue, ref.long_value);
        assertEquals(initialVersion, ref.version);
    }
}
