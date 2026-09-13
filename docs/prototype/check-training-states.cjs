const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');

(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.CHROME_BIN?{executablePath:process.env.CHROME_BIN}:{})});
  const page=await browser.newPage({viewport:{width:430,height:1000}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(pathToFileURL(path.resolve(process.argv[2])).href);
  const f=page.frameLocator('iframe');
  const button=name=>f.getByRole('button',{name,exact:true});
  const click=name=>button(name).click();
  const text=()=>f.locator('#tennis-screen').innerText();
  await click('Открыть');assert.equal(await button('Редактировать').count(),0);
  await click('Закрыть');assert.deepEqual(await f.locator('.tg-keyboard button').allTextContents(),['Открыть']);
  await f.locator('#tennis-role').selectOption('super');await click('Открыть');await click('Редактировать');
  await click('Изменить статус');await click('Завершена');
  assert.match(await text(),/Статус: Учтена/);
  assert.doesNotMatch(await text(),/Баланс указан по этой тренировке/);
  await click('В группе');
  async function alerts(expected){
    for(const role of ['user','admin','super']){
      await f.locator('#tennis-role').selectOption(role);await click('Открыть');
      const dialog=f.getByRole('dialog');assert.equal((await dialog.innerText()).trim(),expected+'\n\nОК');
      await dialog.getByRole('button',{name:'ОК',exact:true}).click();assert.equal(await f.getByRole('dialog').count(),0);
    }
  }
  await alerts('Тренировка завершена');
  await click('У бота');await click('📋 Управление тренировками');await click('Теннис по субботам');
  await f.locator('.tg-keyboard button').filter({hasText:'Теннис в субботу'}).click();
  await click('Изменить статус');await click('Открыта');
  assert.match(await text(),/Статус: Открыта/);assert.doesNotMatch(await text(),/Уточнение|Изменяется/);
  await click('Изменить статус');await click('Отменена');await click('В группе');
  await alerts('Тренировка отменена');
  await click('Открыть');
  await f.getByRole('dialog').waitFor({state:'visible'});
  await page.screenshot({path:path.join(require('node:os').tmpdir(),'tennis-training-alert.png')});
  assert.deepEqual(errors,[]);await browser.close();
  console.log('PASS: single public Open button, personal editing, three states, alerts with OK for every role');
})();
