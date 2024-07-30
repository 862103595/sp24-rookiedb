package edu.berkeley.cs186.database.concurrency;

/**
 * Utility methods to track the relationships between different lock types.
 */
public enum LockType {
    S,   // shared
    X,   // exclusive
    IS,  // intention shared
    IX,  // intention exclusive
    SIX, // shared intention exclusive
    NL;  // no lock held

    /**
     * This method checks whether lock types A and B are compatible with
     * each other. If a transaction can hold lock type A on a resource
     * at the same time another transaction holds lock type B on the same
     * resource, the lock types are compatible.
     */
    public static boolean compatible(LockType a, LockType b) {
        if (a == null || b == null) {
            throw new NullPointerException("null lock type");
        }
        // TODO(proj4_part1): implement

        switch (a) {
            case S:
                return b == S || b == IS  || b == NL;
            case X:
                return b == NL;
            case IS:
                return b == S || b == IS || b == IX || b == SIX || b == NL;
            case IX:
                return b == IS || b == IX || b == NL;
            case SIX:
                return b == IS || b == NL;
            case NL:
                return true;  // NL is compatible with all lock types
            default:
                return false;
        }
    }

    /**
     * This method returns the lock on the parent resource
     * that should be requested for a lock of type A to be granted.
     */
    public static LockType parentLock(LockType a) {
        if (a == null) {
            throw new NullPointerException("null lock type");
        }
        switch (a) {
        case S: return IS;
        case X: return IX;
        case IS: return IS;
        case IX: return IX;
        case SIX: return IX;
        case NL: return NL;
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns if parentLockType has permissions to grant a childLockType
     * on a child.
     */
    public static boolean canBeParentLock(LockType parentLockType, LockType childLockType) {
        if (parentLockType == null || childLockType == null) {
            throw new NullPointerException("null lock type");
        }
        // TODO(proj4_part1): implement
        switch (parentLockType) {
            case NL:
            case X:
            case S:
            case SIX:
                return childLockType == NL;
            case IS:
                return childLockType == IS || childLockType == S || childLockType == NL;
            case IX:
                return true;
            default:
                return false; // For other types like S, X, and NL, there should not be any children locks.
        }

    }

    /**
     * This method returns whether a lock can be used for a situation
     * requiring another lock (e.g. an S lock can be substituted with
     * an X lock, because an X lock allows the transaction to do everything
     * the S lock allowed it to do).
     */
    public static boolean substitutable(LockType substitute, LockType required) {
        if (required == null || substitute == null) {
            throw new NullPointerException("null lock type");
        }
        // TODO(proj4_part1): implement


        switch (required) {
            case X:
                return substitute == LockType.X;
            case SIX:
                return substitute == LockType.X || substitute == LockType.SIX;
            case IX:
                return substitute == LockType.X || substitute == LockType.IX || substitute == LockType.SIX;
            case IS:
                return substitute == LockType.X || substitute == LockType.IX || substitute == LockType.SIX || substitute == LockType.IS || substitute == LockType.S;
            case S:
                return substitute == LockType.X || substitute == LockType.SIX || substitute == LockType.S;
            case NL:
                // No lock (NL) can be substituted by any lock type
                return true;
            default:
                return false;
        }
    }

    /**
     * @return True if this lock is IX, IS, or SIX. False otherwise.
     */
    public boolean isIntent() {
        return this == LockType.IX || this == LockType.IS || this == LockType.SIX;
    }

    @Override
    public String toString() {
        switch (this) {
        case S: return "S";
        case X: return "X";
        case IS: return "IS";
        case IX: return "IX";
        case SIX: return "SIX";
        case NL: return "NL";
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }
}

