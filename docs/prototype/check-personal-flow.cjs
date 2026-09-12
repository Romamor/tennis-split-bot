const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');

(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.CHROME_BIN?{executablePath:process.env.CHROME_BIN}:{})});
  const page=await browser.newPage({viewport:{width:430,height:1000}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(pathToFileURL(path.resolve(process.argv[2])).href);
  const f=page.frameLocator('iframe'),click=name=>f.getByRole('button',{name,exact:true}).click();
  const text=()=>f.locator('#tennis-screen').innerText();
  const lastRow=()=>f.locator('.tg-keyboard .tg-keyrow').last().locator('button').allTextContents();
  await click('Открыть');await click('Присоединиться');await click('Время · 0 ч');await click('+1 ч');await click('⬅️ Назад');
  await click('Оплата · 0 ₽');await click('+50 ₽');await click('⬅️ Назад');await click('Закрыть');await click('У бота');
  assert.doesNotMatch(await text(),/Теннис по субботам/);
  await click('⚙️ Настройки');await click('Тренировка');
  assert.match(await text(),/Укажите параметры тренировки по умолчанию/);assert.deepEqual(await lastRow(),['Назад','Меню']);
  await click('Название');await f.getByRole('textbox',{name:'Ответ'}).fill('Спарринг');await click('Отправить');await click('Сохранить название');
  await click('Время');await click('▲ Часы');await click('✅ Сохранить время');await click('Меню');
  await click('➕ Создать тренировку');assert.deepEqual(await lastRow(),['Отмена']);assert.doesNotMatch(await text(),/Теннис по субботам/);
  await click('Оставить «Спарринг»');assert.deepEqual(await lastRow(),['Назад','Отмена']);
  await click('Продолжить · 13.09.2026');assert.match(await text(),/19:30/);await click('Назад');await click('Назад');
  assert.equal(await f.getByRole('textbox',{name:'Ответ'}).inputValue(),'Спарринг');await click('Отмена');assert.match(await text(),/Что хочешь сделать/);
  for(let i=1;i<=4;i++){
    await click('➕ Создать тренировку');await f.getByRole('textbox',{name:'Ответ'}).fill('Личная '+i);await click('Отправить');
    await click('Продолжить · 13.09.2026');await click('Продолжить · 19:30');
    await click(i%2?'Теннис по субботам':'Вечерний теннис');
    assert.deepEqual(await f.locator('.tg-keyboard button').allTextContents(),['Опубликовать','Назад','Отмена']);
    await click('Назад');assert.match(await text(),/Выбери группу/);await click(i%2?'Теннис по субботам':'Вечерний теннис');await click('Опубликовать');
    assert.deepEqual(await f.locator('.tg-keyboard button').allTextContents(),['Открыть','Редактировать','Назад']);
    await click('Назад');await click('Назад');
  }
  await click('🏓 Мои тренировки');assert.match(await text(),/Тренировок: 5/);assert.match(await text(),/Время: 1 ч/);assert.match(await text(),/Потрачено денег: 50 ₽/);
  const names=await f.locator('.tg-keyboard button').allTextContents();assert.match(names[0],/Личная 4/);assert.match(names[2],/Личная 2/);
  await click('Дальше ›');const second=await f.locator('.tg-keyboard button').allTextContents();assert.match(second[0],/Личная 1/);
  await page.screenshot({path:path.join(require('node:os').tmpdir(),'tennis-personal-list.png')});
  assert.deepEqual(errors,[]);await browser.close();
  console.log('PASS: personal defaults, group-last creation, back/cancel rows and global three-item list');
})();
