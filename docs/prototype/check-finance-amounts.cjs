// Pure rendering/state checks; this does not launch or emulate a browser.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'finance-proposal.html'),'utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const between=(start,end)=>script.slice(script.indexOf(start),script.indexOf(end));
const receiveMarkup=vm.runInNewContext(`
 const esc=x=>String(x);
 ${between('function decorateButton(', 'const nav=')}
 ${between('function receiveButton(', 'const pendingIncoming=')}
 [receiveButton(0),receiveButton(1),receiveButton(7)]
`);
assert.ok(receiveMarkup[0].includes(' disabled'));
assert.ok(!receiveMarkup[0].includes('data-style="success"'));
for(const markup of receiveMarkup.slice(1)) {
 assert.ok(!markup.includes(' disabled'));
 assert.ok(markup.includes('data-style="success"'));
 assert.ok(markup.includes('Принять платёж</button>'));
}
const context=vm.createContext({assert});
vm.runInContext(`
 const state={view:'review',actor:1};
 const group={balances:{1:-500,2:500,3:0},payments:[]};
 const g=()=>group,admin=()=>state.actor===4,esc=x=>String(x),name=id=>'Игрок '+id;
 const money=n=>Math.abs(n)+' ₽';
 const key=(label,action,value,disabled=false)=>'<button'+(disabled?' disabled':'')+'>'+label+'</button>';
 const row=(...buttons)=>'<div>'+buttons.join('')+'</div>';
 ${between('function roundingButton(', 'function refreshPaymentProposal(')}
 ${between('function balance(', 'function peopleTable(')}
 ${between('function amountReset(', 'function editableAmount(')}
 ${between('function paymentPeople(', 'function duplicate(')}
 ${between('function totals(', '// Direct settlement portfolio')}
 ${between('function paymentStatus(', 'const pendingIncoming=')}
 const zero={amount:0};
 assert.ok(amountControls(zero)[0].includes('disabled'),'Rounding stays in place but is disabled at zero');
 const free={amount:0};
 free.amount+=100;
 assert.ok(amountControls(free)[0].includes('disabled'),'Free +100 does not enable rounding');
 free.amount+=50;
 assert.ok(amountControls(free)[0].includes('disabled'),'Free adjustments do not enable rounding');
 free.calculatedAmount=123;
 assert.ok(amountControls(free)[0].includes('disabled'),'Finding a proposal alone does not enable rounding');
 const positive={amount:123,roundingAvailable:true};
 assert.ok(amountControls(positive)[0].includes('Округлить'),'Single rounding action after choosing a balance or proposal');
 assert.equal((amountControls(positive)[0].match(/<button>/g)||[]).length,1,'One rounding button');
 for(const [amount,rounded] of [[49,0],[50,100],[123,100],[149,100],[150,200],[267,300],[300,300]])assert.equal(roundPaymentAmount(amount),rounded);
 const draft={amount:100,calculatedAmount:123,roundingOriginal:123,balancePresetAmount:900};
 restoreAmount(draft);
 assert.equal(draft.amount,123);
 assert.equal(draft.amountText,undefined);
 assert.equal(draft.roundingAvailable,true,'Selecting the calculation enables rounding');
 assert.equal(draft.balancePresetAmount,undefined);
 assert.equal(draft.roundingOriginal,undefined);
 assert.ok(amountReset(draft).includes('disabled'),'Already selected recommendation is disabled');
 assert.equal(amountControls(draft).filter(Boolean).length,3,'All three amount-control rows remain');
 assert.ok(!amountControls(draft)[1].includes('disabled'),'Plus buttons remain active at the recommended amount');
 for(const amount of [0,49,100,123,267])assert.equal(amountControls({...draft,amount}).length,3,'Constant number of rows');
 const message=detail(draft,true);
 assert.ok(message.includes('<h1 class="fp-amount">123 ₽</h1>'),'Amount remains explicitly visible');
 assert.ok(!message.includes('Предлагается ботом'),'Recommendation appears only on its button');
 assert.ok(!message.includes('fp-invisible'),'Message contents stay visible after reset');
 const count=group.payments.length;
 draft.waitingForAmount=true;draft.amountText='126';
 assert.equal(draft.amount,123,'Typing has not committed the new amount');
 commitTypedAmount(draft,'126');
 assert.equal(draft.amount,126);assert.equal(draft.waitingForAmount,false);assert.equal(draft.amountText,undefined);
 assert.equal(state.view,'review','Manual input stays on the current card');
 assert.equal(group.payments.length,count,'Submitting an amount does not record a transfer');
 for(const invalid of ['0','-10','1.5','text']){
  assert.throws(()=>commitTypedAmount(draft,invalid));assert.equal(draft.amount,126);
 }
 assert.ok(amountControls(draft)[0].includes('Округлить'));
 assert.ok(!amountControls(draft)[0].includes('disabled'));
 assert.equal(amountReset({amount:300,balancePresetAmount:323}),'','No separate balance button');
 assert.equal(amountReset({amount:300,roundingOriginal:323}),'','No before-rounding button');
 const resetFree={amount:250};assert.equal(amountReset(resetFree),'','No reset button for an arbitrary amount');assert.throws(()=>restoreAmount(resetFree),/Сумма для возврата недоступна/);assert.equal(resetFree.amount,250);
 assert.ok(amountReset(draft).includes('Предлагается ботом'));
 const pending={id:1,from:1,to:2,amount:100,status:'PENDING'};group.payments.push(pending);
 assert.equal(totals()[1],-500);assert.equal(totals(true)[1],-400);
 const pendingCard=detail(pending);
 assert.ok(pendingCard.includes('fp-negative'));assert.ok(pendingCard.includes('fp-positive'));
 assert.ok(pendingCard.includes('−500 ₽'));assert.ok(pendingCard.includes('+500 ₽'));
 assert.ok(!pendingCard.includes('data-action="use-balance"'),'Stored pending balances are informational');
 state.actor=2;assert.equal(canCancelPayment(pending),false);assert.throws(()=>cancelPayment(pending));
 state.actor=1;assert.equal(canCancelPayment(pending),true);cancelPayment(pending);
 assert.equal(pending.status,'CANCELLED');assert.equal(group.payments.length,1);
 assert.equal(totals()[1],-500);assert.equal(totals(true)[1],-500,'Cancelled transfer no longer reserves a balance');
 const confirmed={id:2,from:1,to:2,amount:200,status:'COMPLETED'};group.payments.push(confirmed);
 assert.ok(!detail(confirmed).includes('fp-balance'),'Confirmed historical transfer does not show current balances');
 assert.equal(canCancelPayment(confirmed),false);assert.throws(()=>cancelPayment(confirmed));
 assert.throws(()=>editPaymentAmount(confirmed,'350'));assert.equal(confirmed.amount,200);
 state.actor=4;editPaymentAmount(confirmed,'350');
 assert.equal(confirmed.amount,350);assert.equal(confirmed.status,'COMPLETED');assert.equal(totals()[1],-150);
 assert.equal(confirmed.audit[0].actor,4);assert.equal(confirmed.audit[0].before,200);assert.equal(confirmed.audit[0].after,350);
 assert.ok(paymentAudit(confirmed).includes('Администратор: Игрок 4'));
 assert.ok(paymentAudit(confirmed).includes('200 ₽ → 350 ₽'));
 cancelPayment(confirmed);assert.equal(confirmed.status,'CANCELLED');assert.equal(totals()[1],-500);
 assert.equal(group.payments.length,2);assert.ok(paymentAudit(confirmed).includes('Отменил перевод'));
 editPaymentAmount(confirmed,'400');assert.equal(confirmed.status,'CANCELLED');assert.equal(totals()[1],-500);
 const otherPending={id:3,from:1,to:2,amount:100,status:'PENDING'};group.payments.push(otherPending);
 editPaymentAmount(otherPending,'200');assert.equal(otherPending.status,'PENDING');
 assert.equal(totals()[1],-500);assert.equal(totals(true)[1],-300);
 assert.equal(historyPayments('all').length,3,'Admin sees all transfers');
 assert.equal(historyPayments('mine').length,0,'Admin personal history remains personal');
 state.actor=1;assert.equal(historyPayments('mine').length,3);
 assert.equal(historyPayments('all').length,3,'Ordinary members can view all transfers');
 state.actor=3;assert.equal(historyPayments('mine').length,0);assert.equal(historyPayments('all').length,3);
 assert.throws(()=>editPaymentAmount(otherPending,'400'),'Reading a transfer does not grant editing rights');
 assert.equal(canCancelPayment(otherPending),false,'Reading another transfer does not grant cancellation rights');
 state.actor=1;editPaymentAmount(otherPending,'350');
 assert.equal(otherPending.amount,350);assert.equal(otherPending.status,'PENDING');
 assert.equal(otherPending.audit.at(-1).admin,false);
 assert.ok(paymentAudit(otherPending).includes('Отправитель:'));
 otherPending.status='COMPLETED';assert.throws(()=>editPaymentAmount(otherPending,'400'));

`,context);
assert.ok(!script.includes("go('amount')"),'No separate manual-entry screen');
assert.ok(!script.includes('amountEntryButtons'),'No duplicate manual-entry keyboard');
console.log('Finance checks passed: current-card input, amount controls, sender cancellation and admin edits/cancellation with audit.');

const notices=vm.runInNewContext(`
 const state={actor:1},name=id=>'Участник '+id,money=n=>n+' ₽',esc=s=>String(s),key=(label,action,value)=>'<button data-style="" data-action="'+action+'" data-value="'+value+'">'+label+'</button>';
 ${between('function hasPaymentNotice(', 'function receiveButton(')}
 const p={id:5,from:2,to:1,amount:600,status:'PENDING',notice:true,time:Date.now()};
 [paymentNotice(p),paymentNotice({...p,to:3}),paymentNotice({...p,notice:false}),paymentNotice({...p,status:'COMPLETED'}),paymentNotice({...p,status:'CANCELLED'}),paymentNotice({...p,time:Date.now()-12*3600000})]
`);
assert.ok(notices[0].includes('Подтвердить получение'));
assert.ok(notices[0].includes('600 ₽'));
assert.ok(notices[0].includes('data-action="notice-open"'));
for(const markup of notices.slice(1))assert.equal(markup,'','No stale, чужое, confirmed, cancelled or expired notification');
