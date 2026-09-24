const {buttonName}=require('./button-locators.cjs');
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
(async()=>{
const browser=await chromium.launch({headless:true,...(process.env.CHROME_BIN?{executablePath:process.env.CHROME_BIN}:{})});
const page=await browser.newPage({viewport:{width:430,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.goto(pathToFileURL(require('node:path').resolve(process.argv[2])).href);
const f=page.frameLocator('iframe'),button=name=>f.getByRole('button',{name:buttonName(name)}),click=name=>button(name).click(),text=()=>f.locator('#fp-screen').innerText();
const role=id=>f.locator('#fp-role').selectOption(String(id));
const amount=async n=>{await f.getByLabel('Сумма в рублях').fill(String(n));await click('Отправить сумму')};
assert.equal(await button('Записать платёж за участников').count(),0);
assert.deepEqual(await f.locator('.fp-keys .fp-row').evaluateAll(rows=>rows.map(r=>[...r.querySelectorAll('button')].map(b=>b.textContent))),[
 ['Отправить платёж','Принять платёж(0)'],['Другой платёж','История платежей'],['💰 Баланс группы'],['⬅️ Назад']]);
assert.equal(await button('Должники').count(),0);
assert.match(await text(),/Баланс: −300 ₽ — тебе осталось внести/);
assert.doesNotMatch(await text(),/Ожидающие платежи пока не меняют баланс/);
await click('Баланс группы');
const balances=()=>f.locator('tbody tr').evaluateAll(rows=>rows.map(r=>[...r.querySelectorAll('td')].map(x=>x.textContent)));
assert.deepEqual(await balances(),[['Борис','+450 ₽'],['Вера','−150 ₽'],['Алексей','−300 ₽'],['Глеб','0 ₽'],['Денис','0 ₽'],['Елена','0 ₽'],['Ирина','0 ₽'],['Кирилл','0 ₽']]);
assert.equal(await button('›').count(),0);await click('Назад');
await click('Другой платёж');assert.equal(await button('Алексей').count(),0);await click('›');assert.equal(await button('Максим').count(),1);await click('‹');await click('Борис');await amount('0');assert.match(await text(),/положительную/);assert.equal(await f.getByLabel('Сумма в рублях').inputValue(),'0');assert.equal(await f.locator('.fp-message input').count(),0);await amount('150');await click('Назад');assert.equal(await f.getByLabel('Сумма в рублях').inputValue(),'150');await click('Отправить сумму');await click('Платёж отправлен');assert.match(await text(),/Ожидает подтверждения/);
await click('К моим финансам');assert.match(await text(),/Отправлено, ждёт подтверждения: 1 · 150 ₽/);await click('Баланс группы');assert.match(await text(),/−300 ₽/);await click('Назад');await click('Отправить платёж');assert.equal(await button('Борис · 150 ₽').count(),1);assert.equal(await button('Борис · 300 ₽').count(),0);await click('Назад');
await click('Другой платёж');await click('Борис');await amount(150);await click('Платёж отправлен');assert.match(await text(),/Это ещё один/);await click('Посмотреть предыдущий');assert.match(await text(),/Ожидает подтверждения/);await click('Назад');await click('Отмена');
await role(2);assert.match(await text(),/Баланс: \+450 ₽ — тебе осталось получить/);assert.match(await text(),/Тебе подтвердить получение: 1 · 150 ₽/);await click('Принять платёж(1)');await click('Алексей · 150 ₽');await click('Да, получил');await click('К моим финансам');assert.equal(await button('Принять платёж(0)').count(),1);assert.doesNotMatch(await text(),/Ожидающие платежи пока не меняют баланс/);await click('Баланс группы');assert.match(await text(),/−150 ₽/);assert.match(await text(),/\+300 ₽/);await click('Назад');
await click('Записать платёж за участников');await click('Глеб');await click('Вера');await amount(75);assert.match(await text(),/Подтверждение участников не требуется/);await click('Записать платёж');assert.match(await text(),/Записал администратор: Борис/);await click('К моим финансам');await click('Баланс группы');assert.match(await text(),/\+75 ₽/);assert.match(await text(),/−225 ₽/);assert.deepEqual((await balances()).map(r=>r[1]),['+300 ₽','+75 ₽','−150 ₽','−225 ₽','0 ₽','0 ₽','0 ₽','0 ₽']);await click('Назад');
await role(3);await click('Принять платёж(0)');assert.match(await text(),/Нет платежей/);await click('Назад');await click('История платежей');await f.locator('[data-action="detail"]').click();assert.match(await text(),/Записал администратор: Борис/);assert.equal(await button('Да, получил').count(),0);
await role(2);await click('Назад');await click('Мои финансы');await click('Вечерний теннис');assert.equal(await button('Записать платёж за участников').count(),0);await click('История платежей');assert.match(await text(),/Платежей пока нет/);
await role(3);assert.equal(await button('Записать платёж за участников').count(),1);
await click('Назад');await click('Мои финансы');await click('Теннис по субботам');
// Counts include all pages, stay within the selected group, and exclude other recipients.
await page.reload();
for(const n of [10,20,30,40]){await click('Другой платёж');await click('Борис');await amount(n);await click('Платёж отправлен');await click('К моим финансам')}
assert.equal(await button('Принять платёж(0)').count(),1);
await role(2);assert.equal(await button('Принять платёж(4)').count(),1);
await click('Назад');await click('Мои финансы');await click('Вечерний теннис');assert.equal(await button('Принять платёж(0)').count(),1);
await click('Назад');await click('Мои финансы');await click('Теннис по субботам');await click('Принять платёж(4)');
assert.equal(await f.locator('[data-action="receive"]').count(),3);await click('›');assert.equal(await f.locator('[data-action="receive"]').count(),1);
await f.locator('[data-action="receive"]').click();await click('Да, получил');await click('К моим финансам');assert.equal(await button('Принять платёж(3)').count(),1);
await role(3);assert.equal(await button('Принять платёж(0)').count(),1);
for(const width of [320,430]){
 await page.setViewportSize({width,height:1000});
 for(const theme of ['light','dark']){
  await page.emulateMedia({colorScheme:theme});
  for(const view of ['menu','balance','amount']){
   if(view==='balance')await click('Баланс группы');
   if(view==='amount'){await click('Другой платёж');await click('Борис')}
   assert.equal(await f.locator('#tennis-finance-proposal').evaluate(e=>e.scrollWidth>e.clientWidth+1),false);
   if(view==='amount'){
    const input=await f.locator('.fp-composer').boundingBox(),keys=await f.locator('.fp-keys').boundingBox();assert.ok(input.y>=keys.y+keys.height);
    await f.getByLabel('Сумма в рублях').fill('125');
   }
   await page.screenshot({path:`/tmp/finance-proposal-v2-${view}-${width}-${theme}.png`,fullPage:true});
   if(view==='balance')await click('Назад');
   if(view==='amount'){await f.getByLabel('Сумма в рублях').press('Enter');assert.match(await text(),/125 ₽/);await click('Отмена')}
  }
 }
}
assert.deepEqual(errors,[]);await browser.close();console.log('PASS: menu rows, incoming counts across pages and groups, balances descending with zeros last, chat amount entry, payment rules and responsive themes');
})();
