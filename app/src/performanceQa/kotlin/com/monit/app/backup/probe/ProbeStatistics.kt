package com.monit.app.backup.probe

internal object ProbeStatistics {
    /** 20개를 모두 확보한 경우만 nearest-rank p95를 반환한다. 누락·실패를 작은 표본으로 바꾸지 않는다. */
    fun p95(values: List<Long>): Long {
        require(values.size == 20 && values.all { it >= 0 })
        return values.sorted()[18]
    }
}
