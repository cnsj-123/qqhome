package com.qq.closie.ui.lifeos.finance

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.qq.closie.R
import com.qq.closie.data.appearance.ThemeTokens
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.LifeFonts
import com.qq.closie.life.ui.theme.applyLifeTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import java.util.Calendar

/** The same native paper is mounted in the overlay or transparent capture Activity. No business writes. */
internal class FinanceNativeReceiptEditor(
    private val context:Context, initial:FinanceEntryDraft, private val snapshot:FinanceSnapshot,
    private val currency:String?, private val source:String, private val tokens:ThemeTokens,
    private val changed:()->Unit, private val confirm:()->Unit, private val later:()->Unit,
    private val handoff:()->Unit,private val canInteract:()->Boolean
) {
    val state=FinanceReceiptEditState(initial)
    private val density=context.resources.displayMetrics.density
    private fun dp(n:Int)=(n*density).toInt()
    private val ink=Color.parseColor(tokens.ink)
    private val secondary=Color.parseColor(tokens.inkSecondary)
    private val accent=Color.parseColor(tokens.accentDeep)
    private val line=Color.parseColor(tokens.line)
    private val mainControls=mutableListOf<View>()
    private val refreshers=mutableListOf<()->Unit>()
    private var rendering=false
    private var imeVisible=false
    private var valid=true
    private val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(30),dp(22),0)}
    private val panel=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(22),dp(24),dp(22),dp(8))}
    private val pages=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;addView(body);addView(panel)}
    val scroll:ScrollView=object:ScrollView(context) {
        private fun route(event:KeyEvent):Boolean {
            if(event.keyCode!=KeyEvent.KEYCODE_BACK)return false
            if(!interactionCurrent())return true
            if(isImeVisible())return false
            if(event.action==KeyEvent.ACTION_UP && !cancelField())later()
            return true
        }
        override fun dispatchKeyEvent(event:KeyEvent)=route(event)||super.dispatchKeyEvent(event)
        override fun dispatchKeyEventPreIme(event:KeyEvent)=route(event)||super.dispatchKeyEventPreIme(event)
    }.apply {isFocusableInTouchMode=true;isFillViewport=false;addView(pages)}
    private val reviewActions=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),dp(14))}
    private val fieldActions=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;setPadding(dp(22),dp(8),dp(22),dp(14));visibility=View.GONE}
    val view:LinearLayout=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        addView(scroll,LinearLayout.LayoutParams(-1,0,1f));addView(reviewActions);addView(fieldActions)
    }
    val message=TextView(context).apply {style(this,10,secondary);visibility=View.GONE;setPadding(0,dp(4),0,dp(4))}
    private val prefix=TextView(context)
    private val amount=EditText(context)
    private val date=EditText(context)
    private val description=EditText(context)
    private val mainConfirm:Button
    private var applyField:(()->Boolean)?=null
    private val choiceLaunchers=mutableMapOf<Button,(String?)->Unit>()

    private fun interactionCurrent()=valid && canInteract()
    private fun View.onReceiptClick(action:()->Unit) {
        setOnClickListener {if(interactionCurrent())action()}
    }
    fun refreshInteraction(){refreshEnabled()}
    fun invalidate() {
        valid=false
        state.cancel()
        applyField=null
        refreshEnabled()
    }

    private fun style(v:TextView,size:Int=12,color:Int=ink,journal:Boolean=true) {
        v.applyLifeTypography(TextStyle(fontFamily=if(journal) LifeFonts.Journal else LifeFonts.JournalMeta,
            fontSize=size.sp,lineHeight=(size+4).sp,letterSpacing=0.sp))
        v.setTextColor(color);v.includeFontPadding=false
    }
    private fun shapeBackground(fill:Int,border:Int?=null,corner:Int=8)=GradientDrawable().apply {
        setColor(fill);cornerRadius=dp(corner).toFloat();border?.let {setStroke(dp(1).coerceAtLeast(1),it)}
    }
    private fun button(label:String,primary:Boolean=false)=Button(context).apply {
        text=label;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
        style(this,if(primary)14 else 12,if(primary)Color.parseColor(tokens.onAccent) else accent)
        background=shapeBackground(if(primary)accent else Color.TRANSPARENT,
            if(primary)null else line,10);setPadding(dp(10),0,dp(10),0)
    }
    private fun fieldRow(label:String,control:View,height:Int=32) {
        val row=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(11),0,0,0)}
        row.addView(TextView(context).apply {text=label;style(this,13,secondary,false)},LinearLayout.LayoutParams(dp(80),-2))
        row.addView(control,LinearLayout.LayoutParams(0,-1,1f))
        body.addView(row,LinearLayout.LayoutParams(-1,dp(height)))
    }
    private fun decorateField(v:TextView,label:String) {
        style(v,12,ink);v.contentDescription=label;v.gravity=Gravity.END or Gravity.CENTER_VERTICAL
        v.background=null;v.setPadding(0,0,dp(2),0);v.minHeight=0;v.minimumHeight=0
        val arrow=ResourcesCompat.getDrawable(context.resources,R.drawable.lifeos_finance_receipt_chevron,context.theme)!!.mutate()
        arrow.setTint(secondary);arrow.setBounds(0,0,dp(12),dp(12));v.setCompoundDrawablesRelative(null,null,arrow,null);v.compoundDrawablePadding=dp(4)
        mainControls+=v
    }
    private fun hideIme(){context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0);view.clearFocus()}
    private fun open(label:String,content:View,onApply:()->Boolean) {
        if(!interactionCurrent() || !state.begin(label))return
        hideIme();panel.removeAllViews()
        panel.addView(button("返回小票 · "+label).apply {onReceiptClick {cancelField()}})
        panel.addView(TextView(context).apply {text=label;style(this,20);setPadding(0,dp(12),0,dp(16))})
        panel.addView(content);applyField=onApply
        body.visibility=View.GONE;panel.visibility=View.VISIBLE
        reviewActions.visibility=View.GONE;fieldActions.visibility=View.VISIBLE
        refreshEnabled();scroll.scrollTo(0,0)
    }
    fun cancelField():Boolean {
        if(!interactionCurrent() || !state.cancel())return false
        returnToReview();return true
    }
    private fun returnToReview() {
        hideIme();applyField=null;panel.removeAllViews();panel.visibility=View.GONE;body.visibility=View.VISIBLE
        fieldActions.visibility=View.GONE;reviewActions.visibility=View.VISIBLE
        refresh();scroll.scrollTo(0,0)
    }
    fun updateImeVisibility(visible:Boolean) {imeVisible=visible}
    private fun isImeVisible()=imeVisible || androidx.core.view.ViewCompat.getRootWindowInsets(view)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
    fun back():Boolean {
        if(!interactionCurrent())return true
        if(isImeVisible()){hideIme();return true}
        return cancelField()
    }
    private fun refreshEnabled() {
        val current=interactionCurrent()
        mainControls.forEach {it.isEnabled=current && state.reviewing}
        mainConfirm.isEnabled=current && state.reviewing
        for(index in 0 until fieldActions.childCount)fieldActions.getChildAt(index).isEnabled=current && !state.saving
    }
    fun startSaving():FinanceEntryDraft?=if(interactionCurrent())state.beginSave()?.also {refreshEnabled()} else null
    fun draft()=state.applied
    private fun refresh() {
        rendering=true
        refreshers.forEach {it()}
        val code=currency ?: snapshot.accounts.firstOrNull {it.id==state.applied.accountId}?.currencyCode
        prefix.text=(when(state.applied.kind){FinanceEditorKind.EXPENSE->"-";FinanceEditorKind.INCOME->"+";FinanceEditorKind.TRANSFER->""})+
            when(code){"CNY"->"¥";null->"待确认币种 ";else->code+" "}
        style(prefix,if(code==null)12 else 32)
        prefix.contentDescription=state.applied.kind.label+"，点击更改收支方向"
        rendering=false;refreshEnabled()
    }
    private fun choice(label:String,options:List<Pair<String,String>>,read:(FinanceEntryDraft)->String,
        write:(FinanceEntryDraft,String)->FinanceEntryDraft,attach:Boolean=true):Button {
        val control=button(label).apply {decorateField(this,label)}
        refreshers+={
            val value=options.firstOrNull {it.first==read(state.applied)}?.second ?: "请选择"
            control.text=if(label=="收付/转出账户" || label=="转账时的转入账户")value.removeSuffix(" · CNY") else value
            androidx.core.view.ViewCompat.setStateDescription(control,value)
            style(control,12)
        }
        val launch:(String?)->Unit={preselected->
            var selected=preselected ?: read(state.applied)
            val choices=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
            val rows=mutableListOf<Pair<String,Button>>()
            fun showSelection(){rows.forEach {(id,b)->b.isSelected=id==selected;b.text=(if(id==selected)"✓ " else "")+options.first {it.first==id}.second}}
            options.forEach {(id,name)->
                val option=button(name).apply {
                    minHeight=dp(44)
                    if(label=="分类" && id.isNotBlank()) {
                        val glyph=FinanceCategoryGlyph.keyForCategory(snapshot,id,snapshot.categories.firstOrNull {it.id==id}?.name.orEmpty())
                        val icon=FinanceCategorySvgDrawable(glyph,ink).apply {setBounds(0,0,dp(20),dp(20))}
                        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=dp(8)
                    }
                    onReceiptClick {selected=id;showSelection()}
                }
                rows+=id to option;choices.addView(option)
            }
            showSelection();open(label,choices) {state.edit {write(it,selected)};true}
        }
        choiceLaunchers[control]=launch
        control.onReceiptClick {launch(null)}
        if(attach)fieldRow(if(label=="收付/转出账户")"账户" else if(label=="转账时的转入账户")"转入账户" else label,control)
        return control
    }
    private fun textEditor(label:String,read:(FinanceEntryDraft)->String,write:(FinanceEntryDraft,String)->FinanceEntryDraft,numeric:Boolean=false):Button {
        val control=button(label).apply {decorateField(this,label)}
        refreshers+={control.text=read(state.applied).ifBlank {"未填写"}}
        control.onReceiptClick {
            val input=EditText(context).apply {
                setText(read(state.applied));contentDescription="编辑"+label;style(this,16);minHeight=dp(48)
                inputType=if(numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else android.text.InputType.TYPE_CLASS_TEXT
            }
            open(label,input) {state.edit {write(it,input.text.toString())};true}
        }
        fieldRow(label,control);return control
    }
    private fun line() {
        val rule=object:View(context) {
            private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=line;strokeWidth=density
                pathEffect=DashPathEffect(floatArrayOf(3*density,4*density),0f)
            }
            override fun onDraw(canvas:Canvas) {canvas.drawLine(0f,height/2f,width.toFloat(),height/2f,stroke)}
        }
        body.addView(rule,LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply {topMargin=dp(10);bottomMargin=dp(10)})
    }

    init {
        val brand=FrameLayout(context)
        brand.addView(TextView(context).apply {
            text="Life OS";style(this,40);typeface=ResourcesCompat.getFont(context,R.font.shalimar_regular);gravity=Gravity.CENTER
        },FrameLayout.LayoutParams(-1,dp(49),Gravity.TOP))
        brand.addView(TextView(context).apply {text="记 账 小 票 · RECORD";style(this,10,secondary,false);gravity=Gravity.END},
            FrameLayout.LayoutParams(-1,dp(14),Gravity.BOTTOM).apply {rightMargin=dp(58)})
        brand.addView(ImageButton(context).apply {background=null;contentDescription="稍后处理并返回";setImageResource(R.drawable.lifeos_finance_entry_back);imageTintList=android.content.res.ColorStateList.valueOf(secondary);onReceiptClick {if(state.reviewing)later()}},
            FrameLayout.LayoutParams(dp(32),dp(40),Gravity.START or Gravity.TOP))
        body.addView(brand,LinearLayout.LayoutParams(-1,dp(64)))
        val kind=choice("类型",FinanceEditorKind.entries.map {it.name to it.label},{it.kind.name},{d,v->d.copy(kind=FinanceEditorKind.valueOf(v))},false)
        val money=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        style(prefix,32);prefix.onReceiptClick {kind.performClick()};prefix.isClickable=true;mainControls+=prefix
        amount.apply {
            setText(initial.amount);contentDescription="金额 · "+(currency ?: "待确认币种")
            background=null;setSingleLine(true);gravity=Gravity.CENTER;setPadding(0,0,0,0);minWidth=dp(48);minimumWidth=0
            hint="待核对金额";setHintTextColor(secondary)
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            style(this,32);imeDone(this)
            addTextChangedListener(watcher {state.changeApplied {it.copy(amount=text.toString())}})
        }
        mainControls+=amount;money.addView(prefix);money.addView(amount,LinearLayout.LayoutParams(-2,-1))
        body.addView(money,LinearLayout.LayoutParams(-1,dp(44)))
        body.addView(TextView(context).apply {text=source+" · 待确认";style(this,10,secondary,false);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(24)))
        line()
        val accounts=listOf("" to "请选择真实账户与币种")+snapshot.accounts.filter {it.archivedAt==null && (currency==null || it.currencyCode==currency)}.map {it.id to (it.name+" · "+it.currencyCode)}
        choice("收付/转出账户",accounts,{it.accountId},{d,v->d.copy(accountId=v)})
        val target=choice("转账时的转入账户",accounts,{it.targetAccountId},{d,v->d.copy(targetAccountId=v)})
        val targetRow=target.parent as View
        refreshers+={targetRow.visibility=if(state.applied.kind==FinanceEditorKind.TRANSFER)View.VISIBLE else View.GONE}
        choice("分类",listOf("" to "未分类/自定义")+snapshot.categories.map {it.id to (FinanceProjection.categoryPath(snapshot.categories,it.id) ?: it.name)},
            {it.categoryId.orEmpty()},{d,v->d.copy(categoryId=v.ifBlank {null})})
        date.apply {
            setText(initial.occurredAt);decorateField(this,"实际发生时间 · 年-月-日 时:分");isFocusable=false
            onReceiptClick {
                val selected=Calendar.getInstance().apply {runCatching {FinanceTime.parse(state.applied.occurredAt)}.getOrNull()?.let {timeInMillis=it}}
                val pickers=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
                val day=DatePicker(context).apply {init(selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH),null)}
                val time=TimePicker(context).apply {setIs24HourView(true);hour=selected.get(Calendar.HOUR_OF_DAY);minute=selected.get(Calendar.MINUTE)}
                pickers.addView(day);pickers.addView(time)
                open("实际发生时间",pickers) {
                    if(!time.validateInput())return@open false
                    day.clearFocus();time.clearFocus()
                    selected.set(day.year,day.month,day.dayOfMonth,time.hour,time.minute,0);selected.set(Calendar.MILLISECOND,0)
                    state.edit {it.copy(occurredAt=FinanceTime.input(selected.timeInMillis))}
                    true
                }
            }
        }
        refreshers+={date.setText(state.applied.occurredAt)};fieldRow("日期",date)
        description.apply {
            setText(initial.description);decorateField(this,"备注（可留空）");setSingleLine(true);imeDone(this)
            addTextChangedListener(watcher {state.changeApplied {it.copy(description=text.toString())}})
        }
        refreshers+={if(description.text.toString()!=state.applied.description)description.setText(state.applied.description)}
        fieldRow("买了什么",description)
        line()
        val chips=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val natureOptions=FinanceNature.entries.map {it.name to when(it){FinanceNature.PERSONAL->"普通消费";FinanceNature.REIMBURSABLE->"可报销";FinanceNature.PROXY_PURCHASE->"代付";else->it.label}}
        val nature=choice("业务性质",natureOptions,{it.relationship.nature.name},{d,v->d.copy(relationship=d.relationship.copy(nature=FinanceNature.valueOf(v)))},false)
        listOf(FinanceNature.PERSONAL,FinanceNature.SPLIT,FinanceNature.REIMBURSABLE,FinanceNature.PROXY_PURCHASE).forEach {n->
            val chip=button(natureOptions.first {it.first==n.name}.second).apply {
                contentDescription="业务性质 · "+natureOptions.first {it.first==n.name}.second
                setPadding(dp(9),0,dp(9),0);onReceiptClick {choiceLaunchers.getValue(nature)(n.name)}
            }
            refreshers+={chip.isSelected=state.applied.relationship.nature==n;chip.background=shapeBackground(if(chip.isSelected)Color.parseColor(tokens.paperTertiary) else Color.TRANSPARENT,if(chip.isSelected)accent else line)}
            chips.addView(chip,LinearLayout.LayoutParams(-2,dp(24)).apply {marginEnd=dp(7)});mainControls+=chip
        }
        body.addView(chips,LinearLayout.LayoutParams(-1,dp(34)))
        val extras=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        val extraStart=body.childCount
        textEditor("自定义主分类（选填）",{it.category},{d,v->d.copy(category=v)})
        textEditor("自定义子分类（选填）",{it.subcategory},{d,v->d.copy(subcategory=v)})
        textEditor("标签 · 逗号分隔",{it.tags},{d,v->d.copy(tags=v)})
        choice("业务性质",natureOptions,{it.relationship.nature.name},{d,v->d.copy(relationship=d.relationship.copy(nature=FinanceNature.valueOf(v)))})
        choice("资金角色",FinanceFlowRole.entries.map {it.name to it.label},{it.relationship.role.name},{d,v->d.copy(relationship=d.relationship.copy(role=FinanceFlowRole.valueOf(v)))})
        choice("关联原事项（退款等需要）",listOf("" to "新事项/无关联")+snapshot.v4?.events.orEmpty().filter {it.voidedAt==null}.map {it.id to it.description.ifBlank {"事项"}},
            {it.relationship.eventId.orEmpty()},{d,v->d.copy(relationship=d.relationship.copy(eventId=v.ifBlank {null},eventUpdatedAt=snapshot.v4?.events.orEmpty().firstOrNull {it.id==v}?.updatedAt))})
        textEditor("个人承担（特殊事项可填，可留空）",{it.relationship.share},{d,v->d.copy(relationship=d.relationship.copy(share=v))},true)
        textEditor("退款/返利冲减个人成本（新经济事项需明确，可填0）",{it.costReductionText},{d,v->d.copy(costReductionText=v)},true)
        while(body.childCount>extraStart){val child=body.getChildAt(extraStart);body.removeView(child);extras.addView(child)}
        extras.addView(button("应用内接力").apply {mainControls+=this;minHeight=dp(40);onReceiptClick {if(state.reviewing)handoff()}})
        val more=button("业务关联与更多信息").apply {onReceiptClick {extras.visibility=if(extras.visibility==View.VISIBLE)View.GONE else View.VISIBLE}}
        body.addView(more,LinearLayout.LayoutParams(-1,dp(30)));mainControls+=more;body.addView(extras)
        reviewActions.addView(message)
        mainConfirm=button("确认记账",true).apply {contentDescription="确认记账";onReceiptClick {if(state.reviewing)confirm()}}
        mainControls+=mainConfirm;reviewActions.addView(mainConfirm,LinearLayout.LayoutParams(-1,dp(37)))
        reviewActions.addView(button("稍后处理 · 进入待确认").apply {
            background=null;mainControls+=this;onReceiptClick {if(state.reviewing)later()}
        },LinearLayout.LayoutParams(-1,dp(30)))
        reviewActions.addView(TextView(context).apply {text="THANK YOU · 收好自己的日子";style(this,9,secondary,false);gravity=Gravity.CENTER})
        fieldActions.addView(button("取消").apply {onReceiptClick {cancelField()}},LinearLayout.LayoutParams(0,dp(42),1f).apply {marginEnd=dp(8)})
        fieldActions.addView(button("应用",true).apply {contentDescription="应用字段编辑";onReceiptClick {
            if(state.field!=null && !state.saving && applyField?.invoke()==true && state.apply()) {
                returnToReview();changed()
            }
        }},LinearLayout.LayoutParams(0,dp(42),1f))
        refresh()
    }
    private fun watcher(update:()->Boolean)=object:TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!rendering && interactionCurrent() && update())changed()}
        override fun afterTextChanged(s:Editable?){}
    }
    private fun imeDone(field:EditText) {
        field.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener {_,action,_->
            if(interactionCurrent() && action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){hideIme();true} else false
        }
    }
    fun showError(text:String){if(valid){message.text=text;message.visibility=View.VISIBLE}}
    fun finishSavingWithError(text:String){if(valid){state.finishSave();refreshEnabled();showError(text)}}
}
