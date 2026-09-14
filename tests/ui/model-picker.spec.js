const { test, expect } = require('@playwright/test');
const snapshot = require('./fixtures/partial-snapshot.json');
const aiModels = require('./fixtures/ai-models.json');
const financialReport = require('./fixtures/financial-report.json');

async function openWorkbench(page) {
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/ai/models') return route.fulfill({ json: aiModels });
    if (path.endsWith('/snapshot')) return route.fulfill({ json: snapshot });
    return route.fulfill({ json: { status: 'UNAVAILABLE', payload: null, issues: ['offline'] } });
  });
  await page.goto('/workbench.html?code=600519');
  await expect(page.locator('#model-picker-label')).toContainText('deepseek · deepseek-chat');
}

async function selectModel(page, modelId) {
  await page.locator('#model-picker-toggle').click();
  await page.locator(`.model-picker-option[data-model-id="${modelId}"]`).click();
  await expect(page.locator('#model-picker-label')).toContainText(modelId);
}

for (const viewport of [{width:1440,height:1000},{width:1024,height:768},{width:768,height:1024},{width:390,height:844}]) {
  test(`model picker shows connectivity states at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openWorkbench(page);

    await expect(page.locator('#model-picker-dot')).toHaveAttribute('data-state', 'ok');
    await page.locator('#model-picker-toggle').click();

    const ok = page.locator('.model-picker-option[data-model-id="deepseek"]');
    await expect(ok.locator('.status-dot')).toHaveAttribute('data-state', 'ok');
    await expect(ok.locator('.model-picker-state')).toContainText('可用');
    await expect(ok.locator('.model-picker-state')).toContainText('412ms');
    await expect(ok.locator('.model-picker-default')).toHaveText('默认');

    const failed = page.locator('.model-picker-option[data-model-id="mimo"]');
    await expect(failed.locator('.status-dot')).toHaveAttribute('data-state', 'failed');
    await expect(failed.locator('.model-picker-state')).toContainText('不可用');

    const unknown = page.locator('.model-picker-option[data-model-id="primary"]');
    await expect(unknown.locator('.status-dot')).toHaveAttribute('data-state', 'unknown');
    await expect(unknown.locator('.model-picker-state')).toContainText('未探测');

    const layout = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }));
    expect(layout.scroll).toBeLessThanOrEqual(layout.width + 1);
    await page.screenshot({ path: `target/model-picker-${viewport.width}.png`, fullPage: true });
  });
}

test('selected model survives a reload and is sent by official FinRobot', async ({ page }) => {
  let body = null;
  await openWorkbench(page);
  await page.route('**/api/finrobot/tasks', (route) => {
    body = route.request().postDataJSON();
    return route.fulfill({ json: { id: 'task-1', code: '600519', status: 'RUNNING', stage: '公司概览', completed: 1 } });
  });
  await page.route('**/api/finrobot/runtime', (route) => route.fulfill({ json: { engine: 'official', installed: true, message: '已安装' } }));
  await page.route('**/api/finrobot/latest/**', (route) => route.fulfill({ status: 204 }));

  await selectModel(page, 'mimo');
  await page.reload();
  await expect(page.locator('#model-picker-label')).toContainText('mimo');

  await page.getByRole('tab', { name: 'FinRobot 投研' }).click();
  await page.getByRole('button', { name: '运行官方投研' }).click();
  await expect.poll(() => body).toBeTruthy();
  expect(body).toEqual({ code: '600519', modelId: 'mimo' });
});

test('selected model is sent by cycle, UZI and financial report modules', async ({ page }) => {
  const bodies = {};
  await page.route('**/api/finrobot/status', (route) => route.fulfill({ json: { enabled: false, status: 'DISABLED_CONFIGURATION_MISSING' } }));
  await page.route('**/api/ai/models', (route) => route.fulfill({ json: aiModels }));
  await page.route('**/api/stocks/**/snapshot', (route) => route.fulfill({ json: snapshot }));
  await page.route('**/api/agent/cycle/tasks', (route) => {
    bodies.cycle = route.request().postDataJSON();
    return route.fulfill({ json: { id: 'cycle-1', code: '600519', status: 'RUNNING', stage: '检索原书' } });
  });
  await page.route('**/api/agent/cycle/latest/**', (route) => route.fulfill({ status: 404, json: { detail: 'none' } }));
  await page.route('**/api/uzi/status', (route) => route.fulfill({ json: { enabled: true, installed: true, pythonAvailable: true, reason: 'READY', rootPath: 'tools/uzi/UZI-Skill', python: 'python' } }));
  await page.route('**/api/uzi/tasks', (route) => {
    bodies.uzi = route.request().postDataJSON();
    return route.fulfill({ json: { id: 'uzi-1', code: '600519', status: 'RUNNING', stage: '研究工作底稿', depth: 'medium', modelName: 'mimo-v2.5-pro', startedAt: '2026-09-14T02:00:00Z', updatedAt: '2026-09-14T02:00:00Z', bundle: null, limitations: [], error: null, reportPath: null } });
  });
  await page.route('**/api/uzi/latest/**', (route) => route.fulfill({ status: 404, json: { detail: 'none' } }));
  await page.route('**/api/agent/financial-report', (route) => {
    bodies.financial = route.request().postDataJSON();
    return route.fulfill({ json: financialReport });
  });

  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
  await expect(page.locator('#model-picker-label')).toContainText('deepseek');
  await selectModel(page, 'mimo');

  await page.getByRole('tab', { name: '周期研究' }).click();
  await page.getByRole('button', { name: '生成周期研究', exact: true }).click();
  await expect.poll(() => bodies.cycle).toBeTruthy();

  await page.getByRole('tab', { name: 'UZI 投研' }).click();
  await page.getByRole('button', { name: '运行 UZI 投研', exact: true }).click();
  await expect.poll(() => bodies.uzi).toBeTruthy();

  await page.getByRole('tab', { name: '财报分析' }).click();
  await page.getByRole('button', { name: '生成财报分析' }).click();
  await expect.poll(() => bodies.financial).toBeTruthy();

  expect(bodies.cycle).toEqual({ code: '600519', modelId: 'mimo' });
  expect(bodies.uzi).toEqual({ code: '600519', depth: 'medium', school: null, modelId: 'mimo' });
  expect(bodies.financial).toEqual({ code: '600519', modelId: 'mimo' });
});

test('selected model is sent by legacy FinRobot and refresh only reloads the catalog', async ({ page }) => {
  let researchBody = null;
  let catalogRequests = 0;
  await page.route('**/api/finrobot/runtime', (route) => route.fulfill({ json: { engine: 'legacy' } }));
  await page.route('**/api/ai/models', (route) => {
    catalogRequests += 1;
    return route.fulfill({ json: aiModels });
  });
  await page.route('**/api/stocks/**/snapshot', (route) => route.fulfill({ json: snapshot }));
  await page.route('**/api/finrobot/research', (route) => {
    researchBody = route.request().postDataJSON();
    return route.fulfill({ json: { status: 'MODEL_FAILED', message: 'stub failure' } });
  });
  await page.route('**/api/finrobot/status', (route) => route.fulfill({ json: { enabled: false, status: 'DISABLED_CONFIGURATION_MISSING' } }));

  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
  await selectModel(page, 'mimo');
  const before = catalogRequests;
  await page.locator('#model-picker-refresh').click();
  await expect.poll(() => catalogRequests).toBe(before + 1);

  await page.getByRole('tab', { name: 'FinRobot 投研' }).click();
  await page.getByRole('button', { name: '运行 FinRobot 投研' }).click();
  await expect.poll(() => researchBody).toBeTruthy();
  expect(researchBody).toEqual({ code: '600519', modelId: 'mimo' });
});
