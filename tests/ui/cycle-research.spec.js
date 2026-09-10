const { test, expect } = require('@playwright/test');
const snapshot = require('./fixtures/partial-snapshot.json');
const path = require('path');

// 合成的研究结果只用于交互测试，不冒充真实模型或原书输出。
const completed = {
  id: 'cycle-demo', code: '600519', status: 'COMPLETED', stage: '研究完成', modelName: 'test-model',
  startedAt: '2026-09-10T02:00:00Z', updatedAt: '2026-09-10T02:01:00Z', snapshotAt: snapshot.fetchedAt,
  skillDigest: 'test-digest', limitations: ['宏观和整体信贷数据尚未接入。'],
  chapters: [{ id: '017-13.md', title: '如何应对市场周期' }],
  evidence: [{ id: 'quote', data: snapshot.quote }],
  trace: [{ at: '2026-09-10T02:00:30Z', action: '阅读原文章节', detail: '如何应对市场周期：已读完' }],
  report: {
    conclusion: '测试结论：证据不足以确认整体市场的周期位置。', calibration: '根据证据充分程度讨论攻守原则。',
    dimensions: ['经济与政策', '企业盈利', '行业供需', '信贷环境', '市场心理', '风险态度', '估值与预期'].map((title, i) => ({
      id: String(i), title, bookView: '测试方法概括：多维度交叉核验。', chapterIds: ['017-13.md'],
      facts: [], analysis: '缺少直接证据，需要保留其他解释。',
    })),
    conflicts: ['个股与整体市场可能不同步。'],
    scenarios: ['偏强', '延续', '偏弱'].map(condition => ({ condition, interpretation: '以新增证据为条件，不预设结果。' })),
    watchItems: ['关注能够推翻现有解释的证据。'], limitations: ['没有信贷证据，不能确认融资环境改善。'],
  },
};
async function setup(page) {
  await page.route('**/api/**', route => route.fulfill({ json: { status: 'UNAVAILABLE', payload: null, issues: [] } }));
  await page.route('**/api/stocks/*/snapshot', route => {
    const code = route.request().url().match(/stocks\/(\d+)\/snapshot/)[1];
    return route.fulfill({ json: { ...snapshot, security: { ...snapshot.security, code } } });
  });
  await page.route('**/api/agent/cycle/models', route => route.fulfill({ json: [{ id: 'deepseek', modelName: 'test-model', defaultModel: true }] }));
  await page.route('**/api/agent/cycle/latest/*', route => route.fulfill({ status: 404, json: { detail: '尚未生成周期报告' } }));
  await page.route('**/api/agent/cycle/tasks', route => {
    expect(route.request().postDataJSON()).toEqual({ code: '600519', modelId: 'deepseek' });
    return route.fulfill({ json: { ...completed, status: 'RUNNING', report: null, stage: '检索原书' } });
  });
  await page.route('**/api/agent/cycle/tasks/cycle-demo', route => route.fulfill({ json: completed }));
  await page.goto('/');
  await page.locator('[data-symbol="600519"]').click();
}
for (const viewport of [{width:1440,height:1000}, {width:1024,height:768}, {width:768,height:1024}, {width:390,height:844}]) {
  test(`independent cycle research at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await setup(page);
    await page.getByRole('tab', { name: '周期研究' }).click();
    await expect(page.getByRole('button', { name: '生成周期研究', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '生成周期研究', exact: true }).click();
    await expect(page.locator('#cycle-output')).toContainText(completed.report.conclusion);
    await expect(page.locator('.cycle-dimension')).toHaveCount(7);
    await expect(page.locator('#cycle-output')).toContainText('书中观点');
    await expect(page.locator('#cycle-output')).toContainText('当前事实');
    await expect(page.locator('#cycle-output')).toContainText('我的分析');
    await expect(page.locator('#cycle-output')).toContainText('整体信贷');
    await page.getByText('原书阅读与执行记录', { exact: true }).click();
    await expect(page.locator('#cycle-output')).toContainText('如何应对市场周期');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const button = page.getByRole('button', { name: '重新生成周期研究', exact: true });
    expect(await button.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
    expect(await page.locator('body').evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgb(247, 247, 244)');
    await page.evaluate(() => window.scrollTo(0, 0));
    const header = await page.locator('.app-header').boundingBox();
    const toolbar = await page.locator('.cycle-toolbar').boundingBox();
    expect(toolbar.y).toBeGreaterThanOrEqual(header.y + header.height);
    await page.screenshot({ path: path.resolve('target/cycle-ui', `cycle-${viewport.width}x${viewport.height}.png`), fullPage: true });
    await page.screenshot({ path: path.resolve('target/cycle-ui', `cycle-viewport-${viewport.width}x${viewport.height}.png`) });
  });
}
test('failure stays local and can retry; generated text is escaped', async ({ page }) => {
  await setup(page);
  await page.route('**/api/agent/cycle/tasks', route => route.fulfill({ json: { ...completed, status: 'FAILED', report: null, error: '原书不可用，请检查配置' } }));
  await page.getByRole('tab', { name: '周期研究' }).click();
  await page.getByRole('button', { name: '生成周期研究', exact: true }).click();
  await expect(page.locator('#cycle-output')).toContainText('原书不可用');
  await page.route('**/api/agent/cycle/tasks', route => route.fulfill({ json: { ...completed, report: { ...completed.report, conclusion: '<img src=x onerror=alert(1)>' } } }));
  await page.getByRole('button', { name: '重新生成周期研究', exact: true }).click();
  await expect(page.locator('#cycle-output')).toContainText('<img src=x');
  await expect(page.locator('#cycle-output img')).toHaveCount(0);
  await page.getByRole('tab', { name: '技术分析', exact: true }).click();
  await expect(page.getByRole('tab', { name: '技术分析', exact: true })).toHaveAttribute('aria-selected', 'true');
});

test('late stock response cannot replace the current security used for cycle research', async ({ page }) => {
  await setup(page);
  await expect(page.locator('#security-code')).toHaveText('600519');
  let release;
  const held = new Promise(resolve => { release = resolve; });
  let requested;
  const requestSeen = new Promise(resolve => { requested = resolve; });
  await page.route('**/api/stocks/600519/snapshot', async route => {
    requested(); await held; await route.fulfill({ json: snapshot });
  });
  await page.locator('#refresh-data').click();
  await requestSeen;
  // 快捷按钮虽位于初始空状态中，事件仍模拟真实的 loadStock 入口。
  await page.locator('[data-symbol="000001"]').evaluate(el => el.click());
  await expect(page.locator('#security-code')).toHaveText('000001');
  release();
  await page.waitForResponse('**/api/stocks/600519/snapshot');
  await page.getByRole('tab', { name: '周期研究' }).click();
  await expect(page.locator('#security-code')).toHaveText('000001');
});

test('long research trace wraps on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.route('**/api/agent/cycle/latest/*', route => route.fulfill({ json: {
    ...completed, status: 'FAILED', report: null, error: '模型研究未完成',
    trace: [{ action: '检索原书', detail: 'a'.repeat(120) }],
  } }));
  await page.getByRole('tab', { name: '周期研究' }).click();
  await page.getByText('原书阅读与执行记录', { exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
