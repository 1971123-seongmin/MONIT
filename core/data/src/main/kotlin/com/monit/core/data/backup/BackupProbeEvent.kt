package com.monit.core.data.backup

internal enum class BackupProbeEvent {
    KDF_ENCRYPT,
    KDF_DECRYPT,
    GCM_ENCRYPT,
    GCM_DECRYPT,
    PARSE,
    LOGICAL_VALIDATION,
    RECOVERY_COPY,
    REPLACEMENT,
    REPLACEMENT_BODY,
    SCHEMA,
    MANIFEST,
    FIELD_KEYS,
    FINANCE,
    EVIDENCE,
    IMPORT,
    MERGED_LIMITS,
    RESTORE_PLAN,
}

internal enum class BackupCheckpoint {
    BEFORE_KDF,
    AFTER_PARTIAL_REPLACE,
    BEFORE_COMMIT,
    AFTER_COMMIT,
}
