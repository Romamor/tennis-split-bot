const {chromium}=require('playwright');
const {buttonName}=require('./button-locators.cjs');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_BIN?{executablePath:process.env.CHROME_BIN}:{})});
 const page=await browser.newPage({viewport:{width:430,height:950}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(pathToFileURL(require('node:path').resolve(process.argv[2])).href);
 const f=page.frameLocator('iframe'),button=name=>f.getByRole('button',{name:buttonName(name)});
 assert.equal(await button('Открыть').getAttribute('data-style'),'primary');assert.equal(await button('Открыть').innerText(),'🏓 Открыть');
 await button('Открыть').click();assert.equal(await button('Присоединиться').getAttribute('data-style'),'primary');
 await button('Присоединиться').click();assert.equal(await button('Не участвую').getAttribute('data-style'),'danger');
 const singles=f.locator('.tg-keyrow');
 assert.ok(await singles.evaluateAll(rows=>rows.every(row=>row.querySelectorAll('button').length!==1||/^[\p{Extended_Pictographic}▲▼‹›]/u.test(row.textContent.trim()))));
 for(const colorScheme of ['light','dark']) {await page.emulateMedia({colorScheme});await page.screenshot({path:'/tmp/button-appearance-'+colorScheme+'.png'})}
 await button('Не участвую').click();assert.equal(await button('Присоединиться').count(),1);
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS: primary Open/join, danger leave, single-row icons and working callbacks');
})();
