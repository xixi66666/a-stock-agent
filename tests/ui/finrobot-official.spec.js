const { test, expect } = require('@playwright/test');
const snapshot = require('./fixtures/partial-snapshot.json');

const report = { schema: 'finrobot-official-v1', engine: 'official-finrobot-equity',
  upstreamCommit: '6d6ccd32c1b8b1904dc656cf06897438aba3daec', ticker: '600519',
  status: 'PARTIAL', modelName: 'fixture-model', sections: {
    company_overview: { title: '公司概览', text: '公司主营业务证据 [S1]', status: 'UNVERIFIED', issues: [] },
    valuation_overview: { title: '估值分析', text: '缺少自由现金流，估值受限', status: 'UNVERIFIED', issues: [] },
    competitor_analysis: { title: '竞争分析', text: '独立同行比较证据', status: 'UNVERIFIED', issues: [] },
    risks: { title: '风险分析', text: '', status: 'UNAVAILABLE', issues: ['专题生成失败'] } },
  valuation: { status: 'UNAVAILABLE', issues: ['没有可靠净债务，不使用默认假设'] },
  sources: [{ id: 'S1', section: 'quote', provider: '同花顺', status: 'HEALTHY', cached: false,
    sourceUrl: 'https://example.org/quote', fetchedAt: '2026-09-14T02:00:00Z' }], limitations: [] };

for (const [width, height] of [[1440, 1000], [1024, 768], [768, 1024], [390, 844]]) {
  test(`official FinRobot task shows distinct sections and downloads at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await page.addInitScript(() => window.localStorage.setItem('astock.selectedModelId', 'fixture'));
    await page.route('**/api/**', route => {
      const path = new URL(route.request().url()).pathname;
      let json = {};
      if (path.endsWith('/snapshot')) json = snapshot;
      if (path.endsWith('/models')) json = { models: [{ id: 'fixture', modelName: 'fixture-model', defaultModel: true }] };
      if (path.endsWith('/runtime')) json = { engine: 'official', installed: true, message: '官方引擎已安装' };
      if (path.includes('/latest/')) return route.fulfill({ status: 204 });
      if (path.endsWith('/tasks')) {
        expect(route.request().postDataJSON()).toEqual({ code: '600519', modelId: 'fixture' });
        json = { id: 'task-1', code: '600519', status: 'RUNNING', stage: '公司概览', completed: 1 };
      }
      if (path.endsWith('/tasks/task-1')) json = { id: 'task-1', code: '600519', status: 'PARTIAL', completed: 8, report };
      return route.fulfill({ json });
    });
    await page.goto('/workbench.html?code=600519&view=finrobot');
    await expect(page.getByText('官方 FinRobot Equity', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '运行官方投研' }).click();
    await expect(page.getByText('部分完成', { exact: true })).toBeVisible();
    await expect(page.getByText('独立同行比较证据')).toBeVisible();
    await expect(page.getByText('专题生成失败', { exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '下载 HTML 报告' })).toHaveAttribute('href', '/api/finrobot/tasks/task-1/artifacts/html');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await page.screenshot({ path: `test-results/finrobot-official-${width}.png`, fullPage: true });
  });
}

async function mockOfficialApis(page, tasks) {
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/snapshot')) return route.fulfill({ json: snapshot });
    if (path.endsWith('/models')) return route.fulfill({ json: { models: [
      { id: 'deepseek', modelName: 'deepseek-flash', defaultModel: true },
      { id: 'mimo', modelName: 'mimo-v2.5-pro', defaultModel: false },
    ] } });
    if (path.endsWith('/runtime')) return route.fulfill({ json: { engine: 'official', installed: true, message: '官方引擎已安装' } });
    if (path.includes('/latest/')) return route.fulfill({ status: 204 });
    if (path.endsWith('/tasks')) return route.fulfill({ json: tasks.start(route.request().postDataJSON()) });
    const match = path.match(/\/tasks\/([^/]+)$/);
    if (match && tasks.get) return route.fulfill({ json: tasks.get(match[1]) });
    return route.fulfill({ json: {} });
  });
}

test('model picker keeps the selected model across tab switches and reloads', async ({ page }) => {
  await mockOfficialApis(page, { start: () => ({ id: 'task-x', code: '600519', status: 'RUNNING', stage: '准备证据', completed: 0 }) });
  await page.goto('/workbench.html?code=600519&view=finrobot');

  await page.locator('#model-picker-toggle').click();
  await page.locator('.model-picker-option[data-model-id="mimo"]').click();
  await expect(page.locator('#model-picker-label')).toContainText('mimo');
  await page.getByRole('tab', { name: '技术分析' }).click();
  await page.getByRole('tab', { name: 'FinRobot 投研' }).click();
  await expect(page.locator('#model-picker-label')).toContainText('mimo');
  await page.reload();
  await expect(page.locator('#model-picker-label')).toContainText('mimo');
});

test('official FinRobot status displays the model actually running', async ({ page }) => {
  await mockOfficialApis(page, {
    start: (body) => {
      expect(body).toEqual({ code: '600519', modelId: 'mimo' });
      return { id: 'task-mimo', code: '600519', modelName: 'mimo-v2.5-pro', status: 'RUNNING', stage: '公司概览', completed: 1 };
    },
    get: () => ({ id: 'task-mimo', code: '600519', modelName: 'mimo-v2.5-pro', status: 'RUNNING', stage: '竞争分析', completed: 3 }),
  });
  await page.goto('/workbench.html?code=600519&view=finrobot');

  await page.locator('#model-picker-toggle').click();
  await page.locator('.model-picker-option[data-model-id="mimo"]').click();
  await page.getByRole('button', { name: '运行官方投研' }).click();
  await expect(page.locator('.official-task-status')).toContainText('模型：mimo-v2.5-pro');
  await expect(page.locator('.official-task-status')).toContainText('竞争分析');
});
