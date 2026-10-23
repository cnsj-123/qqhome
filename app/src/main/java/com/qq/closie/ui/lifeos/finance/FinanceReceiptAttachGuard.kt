package com.qq.closie.ui.lifeos.finance

import com.qq.closie.data.backup.RestoreStartupGate

/** No suspension between the last eligibility check and the native addView call. */
internal fun attachFinanceReceipt(
    expectedRestoreGeneration:Long, canPresent:()->Boolean, authorized:()->Boolean, addView:()->Unit
):Boolean = RestoreStartupGate.withBusinessAccess {
    if (RestoreStartupGate.state.value.restoreGeneration!=expectedRestoreGeneration || !canPresent() || !authorized()) false
    else {addView();true}
}
