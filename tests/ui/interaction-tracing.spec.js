const { test, expect } = require('@playwright/test');
const snapshot = require('./fixtures/partial-snapshot.json');

test('tracing also works on HTTP hosts without crypto.randomUUID', async ({ page }) => {
  const events = [];
  await page.addInitScript(() => Object.defineProperty(crypto, 'randomUUID', { value: undefined }));
  await page.route('**/api/**', route => {
    if (route.request().url().includes('/observability/events')) {
      events.push(route.request().postDataJSON());
      return route.fulfill({ status: 204 });
    }
    return route.fulfill({ json: route.request().url().includes('/snapshot') ? snapshot : { enabled: false } });
  });
  await page.goto('/workbench.html?code=600519');
  await page.locator('[data-view="uzi"]').click();
  await expect.poll(() => events.some(event => event.type === 'click' && event.view === 'uzi')).toBe(true);
});

test('every module click is reported even without a business request, and API calls carry context', async ({ page }) => {
  const events = [];
  const requests = [];
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', async route => {
    const request = route.request();
    if (request.url().includes('/observability/events')) {
      events.push(request.postDataJSON());
      return route.fulfill({ status: 204 });
    }
    requests.push({ url: request.url(), headers: request.headers() });
    return route.fulfill({ json: request.url().includes('/snapshot') ? snapshot : { enabled: false } });
  });
  await page.goto('/workbench.html?code=600519&token=must-not-log');
  await expect(page.locator('#security-code')).toHaveText('600519');
  for (const view of ['capital', 'technical', 'fundamentals', 'financial', 'valuation', 'cycle', 'uzi', 'events', 'sources', 'finrobot']) {
    await page.locator(`[data-view="${view}"]`).click();
    await expect.poll(() => events.some(event => event.type === 'click' && event.view === view)).toBe(true);
  }
  const uziClick = events.find(event => event.type === 'click' && event.view === 'uzi');
  await expect.poll(() => requests.some(request => request.url.includes('/api/uzi/')
    && request.headers['x-interaction-id'] === uziClick.interactionId
    && request.headers['x-page-id'] === uziClick.pageId)).toBe(true);
  expect(JSON.stringify(events)).not.toContain('must-not-log');
  expect(events.some(event => event.type === 'page')).toBe(true);
});

test('nested icons, keyboard submit and dynamic controls are captured; failed telemetry does not block clicks', async ({ page }) => {
  const events = [];
  await page.route('**/api/**', route => {
    if (route.request().url().includes('/observability/events')) {
      events.push(route.request().postDataJSON());
      return route.abort();
    }
    return route.fulfill({ json: { enabled: false } });
  });
  await page.goto('/workbench.html');
  await page.evaluate(() => {
    const panel = document.createElement('div');
    panel.innerHTML = '<button id="dynamic-action"><span>测试图标</span></button><form id="dynamic-form"><input aria-label="测试输入"></form>';
    document.body.prepend(panel);
    panel.querySelector('button').onclick = () => document.body.dataset.clicked = 'yes';
    panel.querySelector('form').onsubmit = event => event.preventDefault();
  });
  await page.locator('#dynamic-action span').click();
  await expect(page.locator('body')).toHaveAttribute('data-clicked', 'yes');
  await page.getByRole('textbox', { name: '测试输入' }).fill('private-input');
  await page.getByRole('textbox', { name: '测试输入' }).press('Enter');
  await expect.poll(() => events.some(event => event.type === 'submit' && event.target.includes('dynamic-form'))).toBe(true);
  expect(events.some(event => event.type === 'click' && event.target.includes('dynamic-action'))).toBe(true);
  expect(JSON.stringify(events)).not.toContain('private-input');
});
