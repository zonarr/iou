// End-to-end smoke test of the trip workflow in a phone-sized browser.
//
//   1. start the app (./mvnw spring-boot:run) with a database
//   2. npm install playwright   (once, anywhere)
//   3. node e2e/smoke.mjs [baseUrl] [screenshotDir]
//
// It registers two fresh accounts each run, so it can be run against any environment.
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const base = process.argv[2] || 'http://localhost:8080';
const shots = process.argv[3] || 'e2e/screenshots';
mkdirSync(shots, { recursive: true });
const stamp = Date.now();
const phone = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'en-GB' };

let step = 0;
async function shot(page, name) {
  step += 1;
  await page.waitForTimeout(250);
  await page.screenshot({ path: `${shots}/${String(step).padStart(2, '0')}-${name}.png` });
}
function check(condition, message) {
  if (!condition) throw new Error(`FAILED: ${message}`);
  console.log(`  ok  ${message}`);
}
async function watch(page, who) {
  page.on('pageerror', (e) => { throw new Error(`${who}: page error: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error') console.log(`  [${who} console] ${m.text()}`); });
  page.on('dialog', (d) => d.accept());
}
async function register(page, name) {
  await page.getByRole('button', { name: /Create an account/ }).click();
  await page.getByLabel('Your name').fill(name);
  await page.getByLabel('Email').fill(`${name.toLowerCase()}-${stamp}@example.com`);
  await page.getByLabel('Password').fill('correct horse battery');
  await page.getByRole('button', { name: 'Create account' }).click();
}
async function addExpense(page, { what, amount, currency, rate, expectRate }) {
  await page.getByRole('button', { name: /Add expense/ }).click();
  await page.getByLabel('What was it for?').fill(what);
  await page.getByLabel('Amount').fill(amount);
  if (currency) await page.getByRole('radio', { name: currency, exact: true }).click();
  if (expectRate) check((await page.getByLabel('Exchange rate').inputValue()) === expectRate, `rate pre-filled with ${expectRate}`);
  if (rate) await page.getByLabel('Exchange rate').fill(rate);
}

const browser = await chromium.launch();
try {
  // ---------------- Dad sets up the trip
  const dadCtx = await browser.newContext(phone);
  const dad = await dadCtx.newPage();
  await watch(dad, 'dad');
  await dad.goto(base);
  await shot(dad, 'login');
  await register(dad, 'Dad');
  await dad.getByText('All settled up').waitFor();
  await shot(dad, 'home-empty');

  await dad.getByRole('button', { name: /New group/ }).click();
  await dad.getByLabel('Group name').fill('Europe 2026');
  await dad.getByRole('button', { name: 'Create', exact: true }).click();
  await dad.getByRole('heading', { name: 'Invite link' }).waitFor();
  for (const person of ['Mum', 'Grandma']) {
    await dad.getByLabel('Name').fill(person);
    await dad.getByRole('button', { name: /Add$/ }).click();
    await dad.locator('.people').getByText(person, { exact: true }).waitFor();
  }
  const inviteUrl = await dad.getByLabel('Invite link').inputValue();
  check(inviteUrl.includes('/#/join/'), 'invite link is shown');
  await shot(dad, 'people');

  // ---------------- first expense, in euros
  await dad.getByRole('tab', { name: 'Expenses' }).click();
  await addExpense(dad, { what: 'Dinner in Paris', amount: '100', currency: 'EUR', rate: '38.5' });
  await dad.getByText('= ฿3,850.00').waitFor();
  await shot(dad, 'expense-form');
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Dinner in Paris' }).waitFor();
  check(await dad.getByText('€100.00').first().isVisible(), 'original amount shown in the feed');
  check(await dad.getByText('฿3,850.00').first().isVisible(), 'THB amount shown next to it');
  await shot(dad, 'feed');

  // ---------------- second euro expense: the rate is remembered
  await addExpense(dad, { what: 'Metro tickets', amount: '8.40', currency: 'EUR', expectRate: '38.5' });
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Metro tickets' }).waitFor();

  // ---------------- Mum opens the invite link on her phone
  const mumCtx = await browser.newContext(phone);
  const mum = await mumCtx.newPage();
  await watch(mum, 'mum');
  await mum.goto(inviteUrl);
  await mum.getByText('You were invited to a group').waitFor();
  await register(mum, 'Mother');
  await mum.getByText('Who are you?').waitFor();
  await shot(mum, 'join');
  await mum.getByRole('button', { name: 'I am Mum' }).click();
  await mum.getByRole('heading', { name: 'Dinner in Paris' }).waitFor();
  check(await mum.getByText(/You owe Dad ฿1,283\.\d\d/).first().isVisible(), 'the placeholder\'s share moved to Mum\'s account');
  await shot(mum, 'mum-feed');

  // ---------------- a new expense now needs Mum's answer
  await dad.reload();
  await addExpense(dad, { what: 'Museum', amount: '900' });
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Museum' }).waitFor();
  check(await dad.getByText(/Waiting for Mother/).isVisible(), 'Dad sees that Mum has not answered yet');

  await mum.getByRole('button', { name: /^‹$/ }).click().catch(() => mum.goto(base));
  await mum.goto(`${base}/#/`);
  await mum.getByText('1 thing needs your answer').click();
  await shot(mum, 'pending');
  await mum.getByRole('button', { name: 'Accept', exact: true }).click();
  await mum.getByText('Nothing is waiting for you.').waitFor();
  check(true, 'Mum accepted her share');

  // ---------------- balances and the settle-up plan
  await mum.goto(`${base}/#/`);
  await mum.getByRole('button', { name: /Europe 2026/ }).click();
  await mum.getByRole('tab', { name: 'Balances' }).click();
  await mum.getByText('Settle up').first().waitFor();
  await shot(mum, 'balances');
  check(await mum.getByText(/Mother pays Dad/).isVisible(), 'settle-up plan tells Mum to pay Dad');
  await mum.locator('.transfer.mine').getByRole('button', { name: 'Record this payment' }).click();
  await mum.getByLabel('Amount').waitFor();
  const suggested = await mum.getByLabel('Amount').inputValue();
  check(Number(suggested) > 0, `payment form pre-filled with ${suggested}`);
  await shot(mum, 'payment-form');
  await mum.getByRole('button', { name: 'Save' }).click();
  await mum.getByText('Settle up').first().waitFor();
  check(await mum.getByText(/1 entry is still pending/).isVisible(), 'the payment is pending until Dad confirms');

  // ---------------- Dad confirms he received it
  await dad.goto(`${base}/#/`);
  await dad.getByText('1 thing needs your answer').click();
  await dad.getByRole('button', { name: 'I received it' }).click();
  await dad.getByText('Nothing is waiting for you.').waitFor();
  await mum.reload();
  await mum.getByText('Settle up').first().waitFor();
  check(!(await mum.getByText(/Mother pays Dad/).isVisible()), 'after confirmation Mum owes nothing');
  await shot(mum, 'balances-after');

  // ---------------- trusted group: new expenses count at once
  await dad.goto(`${base}/#/`);
  await dad.getByRole('button', { name: /Europe 2026/ }).click();
  await dad.getByRole('tab', { name: 'People' }).click();
  await dad.getByRole('checkbox').check();
  await dad.getByText('✓ Trusted group').waitFor();
  await dad.getByRole('tab', { name: 'Expenses' }).click();
  await addExpense(dad, { what: 'Cable car', amount: '120', currency: 'CHF', rate: '43.21' });
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Cable car' }).waitFor();
  check(!(await dad.getByText(/Waiting for/).isVisible()), 'nothing is pending in a trusted group');
  await dad.getByRole('heading', { name: 'Cable car' }).click();
  await shot(dad, 'expense-details');

  // ---------------- simple IOU, then edit and delete it
  await dad.getByRole('button', { name: 'Simple IOU' }).click();
  await dad.getByRole('radiogroup').nth(0).getByRole('radio', { name: /Grandma/ }).click();
  await dad.getByRole('radiogroup').nth(1).getByRole('radio', { name: /Dad/ }).click();
  await dad.getByLabel('Amount').fill('55.50');
  await dad.getByLabel('What was it for?').fill('Souvenir');
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Souvenir' }).click();
  check(await dad.getByText('Grandma owes Dad').isVisible(), 'simple IOU appears in the feed');
  await dad.getByRole('button', { name: 'Edit' }).click();
  await dad.getByLabel('Amount').fill('60');
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByText('฿60.00').waitFor();
  await dad.getByRole('heading', { name: 'Souvenir' }).click();
  await dad.getByRole('button', { name: 'Delete' }).click();
  await dad.getByRole('heading', { name: 'Souvenir' }).waitFor({ state: 'detached' });
  check(true, 'IOU edited and deleted');

  // ---------------- edit an expense
  await dad.getByRole('heading', { name: 'Museum' }).click();
  await dad.getByRole('button', { name: 'Edit' }).click();
  check((await dad.getByLabel('Amount').inputValue()) === '900.00', 'edit form is filled with the saved expense');
  await dad.getByLabel('Amount').fill('1200');
  await dad.getByRole('checkbox', { name: /Grandma/ }).click();
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByText('฿1,200.00').waitFor();
  check(true, 'expense edited');

  // ---------------- custom split and two payers
  await dad.getByRole('button', { name: /Add expense/ }).click();
  await dad.getByLabel('What was it for?').fill('Ski passes');
  await dad.getByLabel('Amount').fill('300');
  await dad.getByRole('button', { name: 'More than one person paid' }).click();
  await dad.getByRole('checkbox', { name: /Mother/ }).first().click();
  await dad.locator('.value-row', { hasText: 'Dad' }).first().locator('input').fill('200');
  await dad.getByText('฿100.00 of the total not yet assigned to a payer').waitFor();
  await dad.locator('.value-row', { hasText: 'Mother' }).first().locator('input').fill('100');
  await dad.getByText('Payers add up to the total').waitFor();
  await dad.getByRole('radio', { name: 'Shares' }).click();
  await dad.locator('.value-row', { hasText: 'Grandma' }).locator('input').fill('2');
  await shot(dad, 'custom-split');
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Ski passes' }).click();
  // 300 by shares Dad 1 : Grandma 2 : Mother 1 = 75 / 150 / 75.
  check(await dad.getByText('2 people paid').isVisible(), 'two payers are shown');
  check(await dad.locator('.details').getByText('฿150.00').isVisible(), 'Grandma has a double share');
  await dad.getByRole('button', { name: 'Edit' }).click();
  check(await dad.getByRole('radio', { name: 'Shares' }).getAttribute('aria-checked') === 'true', 'edit form remembers the split type');
  check((await dad.locator('.value-row', { hasText: 'Grandma' }).locator('input').inputValue()) === '2', 'edit form remembers the shares');
  await dad.getByRole('radio', { name: 'Percent' }).click();
  await dad.locator('.value-rows').last().locator('input').nth(0).fill('50');
  await dad.locator('.value-rows').last().locator('input').nth(1).fill('30');
  await dad.getByText('20% left').waitFor();
  await dad.locator('.value-rows').last().locator('input').nth(2).fill('20');
  await dad.getByText('Adds up to 100%').waitFor();
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Ski passes' }).waitFor();
  check(true, 'custom split edited to percentages');

  // ---------------- one-on-one, outside the group
  await dad.goto(`${base}/#/direct`);
  await dad.getByRole('button', { name: /Add/ }).click();
  await dad.getByLabel('Their email').fill(`mother-${stamp}@example.com`);
  await dad.getByRole('button', { name: 'Find' }).click();
  await dad.getByText('Found Mother').waitFor();
  await dad.getByRole('button', { name: 'They owe me' }).click();
  await dad.getByLabel('Amount').fill('250');
  await dad.getByLabel('What was it for?').fill('Phone top-up');
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Phone top-up' }).waitFor();
  check(await dad.getByText(/Waiting for Mother/).isVisible(), 'one-on-one IOU waits for the other person');
  await shot(dad, 'direct');
  await dad.getByRole('button', { name: /Add/ }).click();
  await dad.getByRole('radio', { name: 'Mother' }).click();
  await dad.getByRole('button', { name: 'I paid, split in two' }).click();
  await dad.getByLabel('What was it for?').fill('Airport taxi');
  await dad.getByLabel('Amount').fill('800');
  await dad.getByRole('button', { name: 'Save' }).click();
  await dad.getByRole('heading', { name: 'Airport taxi' }).waitFor();
  check(true, 'one-on-one split recorded');

  // ---------------- history
  await dad.goto(`${base}/#/`);
  await dad.getByRole('button', { name: /Europe 2026/ }).click();
  await dad.getByRole('tab', { name: 'History' }).click();
  await dad.getByText('Dad added expense “Dinner in Paris”').waitFor();
  check(await dad.getByText('€100.00 (฿3,850.00)').isVisible(), 'history shows the original and THB amounts');
  check(await dad.getByText('Mother is “Mum”').isVisible(), 'history shows Mum claiming her placeholder');
  check(await dad.getByText(/was ฿900\.00/).isVisible(), 'history shows what an edited expense used to be');
  check(await dad.getByText('Trusted group switched on').isVisible(), 'history shows the settings change');
  await shot(dad, 'history');
  await dad.goto(`${base}/#/activity`);
  await dad.getByText('Dad added: Mother owes Dad').waitFor();
  check(true, 'my history includes one-on-one entries');

  // ---------------- Thai
  await dad.goto(`${base}/#/me`);
  await dad.getByRole('button', { name: 'ไทย' }).click();
  await dad.getByRole('button', { name: 'ออกจากระบบ' }).waitFor();
  await shot(dad, 'me-thai');
  await dad.getByRole('button', { name: /หน้าหลัก/ }).click();
  await dad.getByText('กลุ่ม', { exact: true }).waitFor();
  await shot(dad, 'home-thai');
  check(true, 'Thai interface works');

  console.log('\nSmoke test passed.');
} finally {
  await browser.close();
}
