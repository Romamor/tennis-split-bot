const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
(async()=>{
const browser=await chromium.launch({headless:true,...(process.env.CHROME_BIN?{executablePath:process.env.CHROME_BIN}:{})});
const page=await browser.newPage({viewport:{width:430,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.goto(pathToFileURL(require('node:path').resolve(process.argv[2])).href);
const f=page.frameLocator('iframe'),button=name=>f.getByRole('button',{name,exact:true}),click=name=>button(name).click(),text=()=>f.locator('#fp-screen').innerText();
const role=id=>f.locator('#fp-role').selectOption(String(id));
const amount=async n=>{await f.getByLabel('Сумма в рублях').fill(String(n));await click('Далее')};
assert.equal(await button('Записать платёж за участников').count(),0);
await click('Другой платёж');assert.equal(await button('Алексей').count(),0);await click('›');assert.equal(await button('Максим').count(),1);await click('‹');await click('Борис');await amount('0');assert.match(await text(),/положительную/);await amount('150');await click('Назад');assert.equal(await f.getByLabel('Сумма в рублях').inputValue(),'150');await click('Далее');await click('Платёж отправлен');assert.match(await text(),/Ожидает подтверждения/);
await click('К моим финансам');await click('Баланс группы');assert.match(await text(),/−300 ₽/);await click('Назад');await click('Отправить платёж');assert.equal(await button('Борис · 150 ₽').count(),1);assert.equal(await button('Борис · 300 ₽').count(),0);await click('Назад');
await click('Другой платёж');await click('Борис');await amount(150);await click('Платёж отправлен');assert.match(await text(),/Это ещё один/);await click('Посмотреть предыдущий');assert.match(await text(),/Ожидает подтверждения/);await click('Назад');await click('Отмена');
await role(2);await click('Принять платёж');await click('Алексей · 150 ₽');await click('Да, получил');await click('К моим финансам');await click('Баланс группы');assert.match(await text(),/−150 ₽/);assert.match(await text(),/\+300 ₽/);await click('Назад');
await click('Записать платёж за участников');await click('Глеб');await click('Вера');await amount(75);assert.match(await text(),/Подтверждение участников не требуется/);await click('Записать платёж');assert.match(await text(),/Записал администратор: Борис/);await click('К моим финансам');await click('Баланс группы');assert.match(await text(),/\+75 ₽/);assert.match(await text(),/−225 ₽/);await click('Назад');
await role(3);await click('Принять платёж');assert.match(await text(),/Нет платежей/);await click('Назад');await click('История платежей');await f.locator('[data-action="detail"]').click();assert.match(await text(),/Записал администратор: Борис/);assert.equal(await button('Да, получил').count(),0);
await role(2);await click('Назад');await click('Вечерний теннис');assert.equal(await button('Записать платёж за участников').count(),0);await click('История платежей');assert.match(await text(),/Платежей пока нет/);
await role(3);assert.equal(await button('Записать платёж за участников').count(),1);
await click('Назад');await click('Теннис по субботам');
for(const width of [320,430]){await page.setViewportSize({width,height:1000});for(const theme of ['light','dark']){await page.emulateMedia({colorScheme:theme});assert.equal(await f.locator('#tennis-finance-proposal').evaluate(e=>e.scrollWidth>e.clientWidth+1),false);await page.screenshot({path:`/tmp/finance-proposal-${width}-${theme}.png`,fullPage:true})}}
assert.deepEqual(errors,[]);await browser.close();console.log('PASS: personal pending/acceptance, duplicate, partial suggestion, admin direct accounting, role/group isolation, pagination, back navigation, narrow layout and themes');
})();
