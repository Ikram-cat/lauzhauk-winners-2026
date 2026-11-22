package com.cffplanb.backend.service

import com.cffplanb.backend.domain.AdviceCode
import com.cffplanb.backend.domain.DurationEstimate

/** Port of analytics/sbb_duration/advice.py, applied to unrounded minutes. The UI localizes the code. */
object AdviceService {
    fun advise(estimate: DurationEstimate?, detourExtraMinutes: Int?): AdviceCode {
        if (estimate == null || detourExtraMinutes == null) return AdviceCode.UNKNOWN
        return when {
            detourExtraMinutes < estimate.p50Minutes -> AdviceCode.TAKE_DETOUR
            detourExtraMinutes < estimate.pessimisticMinutes -> AdviceCode.DETOUR_IF_DEADLINE
            else -> AdviceCode.WAIT
        }
    }
}
