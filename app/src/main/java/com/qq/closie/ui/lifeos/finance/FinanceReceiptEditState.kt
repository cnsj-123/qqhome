package com.qq.closie.ui.lifeos.finance

/** Only the applied draft can be saved or confirmed. A field editor never mutates it in place. */
internal class FinanceReceiptEditState(initial:FinanceEntryDraft) {
    var applied=initial
        private set
    var field:String?=null
        private set
    var pending:FinanceEntryDraft?=null
        private set
    var saving=false
        private set
    val reviewing get()=field==null && !saving

    fun begin(field:String):Boolean {
        if(!reviewing)return false
        this.field=field;pending=applied
        return true
    }
    fun edit(change:(FinanceEntryDraft)->FinanceEntryDraft) {
        check(field!=null && !saving)
        pending=change(requireNotNull(pending))
    }
    fun apply():Boolean {
        if(field==null || saving)return false
        applied=requireNotNull(pending)
        field=null;pending=null
        return true
    }
    fun cancel():Boolean {
        if(field==null || saving)return false
        field=null;pending=null
        return true
    }
    fun changeApplied(change:(FinanceEntryDraft)->FinanceEntryDraft):Boolean {
        if(!reviewing)return false
        applied=change(applied)
        return true
    }
    fun beginSave():FinanceEntryDraft? {
        if(!reviewing)return null
        saving=true
        return applied
    }
    fun finishSave(){saving=false}
}
